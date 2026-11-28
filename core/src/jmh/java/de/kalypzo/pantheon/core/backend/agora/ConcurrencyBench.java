package de.kalypzo.pantheon.core.backend.agora;

import static de.kalypzo.pantheon.core.backend.agora.Bench.COINS;

import com.zaxxer.hikari.HikariDataSource;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;

/**
 * S4: confirmed {@code +1} per second under contention, on one hot row or spread over 10k rows. Pantheon writes from
 * two nodes (threads alternate), the baseline once naively (read, add, write back) and once atomically. After the
 * trial the stored sum is compared with the confirmed writes: Pantheon and the atomic baseline must lose nothing.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class ConcurrencyBench {

    private static final int SPREAD = 10_000;

    @Param({"pantheon", "jdbcNaive", "jdbcAtomic"})
    public String impl;

    @Param({"hot", "spread"})
    public String rows;

    @Param({"16"})
    public int pool;

    private AgoraContractFixture fx;
    private Backend[] nodes;
    private HikariDataSource ds;
    private long base;
    private final LongAdder done = new LongAdder();
    private final AtomicInteger threadIds = new AtomicInteger();
    private int threads;

    @State(Scope.Thread)
    public static class Worker {
        int id = -1;
    }

    @Setup(Level.Trial)
    public void setUp(BenchmarkParams params) {
        threads = params.getThreads();
        base = ThreadLocalRandom.current().nextLong(); // fresh rows per trial
        ds = Bench.pool(pool);
        if (impl.equals("pantheon")) {
            fx = new AgoraContractFixture(pool);
            nodes = new Backend[] {fx.node("lobby-01"), fx.node("lobby-02")};
        } else {
            Bench.sql(ds, "CREATE TABLE IF NOT EXISTS bench_conc (id bigint PRIMARY KEY, coins bigint NOT NULL)",
                    "TRUNCATE bench_conc",
                    "INSERT INTO bench_conc SELECT i, 0 FROM generate_series(0, " + (SPREAD - 1) + ") i");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        long stored = impl.equals("pantheon")
                ? Bench.scalar(ds, "SELECT coalesce(sum((data->>'coins')::bigint), 0) FROM pantheon_player "
                        + "WHERE namespace = 'bench' AND player BETWEEN '" + player(0) + "' AND '" + player(SPREAD) + "'")
                : Bench.scalar(ds, "SELECT sum(coins) FROM bench_conc");
        long confirmed = done.sum();
        long lost = confirmed - stored;
        Bench.record("consistency.jsonl", String.format(
                "{\"impl\": \"%s\", \"rows\": \"%s\", \"pool\": %d, \"threads\": %d, \"confirmed\": %d, "
                        + "\"stored\": %d, \"lost\": %d}", impl, rows, pool, threads, confirmed, stored, lost));
        ds.close();
        if (fx != null) fx.close();
        if (lost != 0 && !impl.equals("jdbcNaive")) {
            throw new IllegalStateException(impl + " lost " + lost + " of " + confirmed + " updates");
        }
    }

    private UUID player(int i) {
        return new UUID(base, i);
    }

    @Benchmark
    public void inc(Worker w) throws SQLException {
        if (w.id < 0) w.id = threadIds.getAndIncrement();
        int row = rows.equals("hot") ? 0 : ThreadLocalRandom.current().nextInt(SPREAD);
        switch (impl) {
            case "pantheon" -> {
                Backend node = nodes[w.id % nodes.length];
                if (!Bench.get(node.apply(Bench.write(new PlayerDoc(player(row)), Bench.inc(COINS, 1)))).applied()) {
                    return;
                }
            }
            case "jdbcNaive" -> {
                try (Connection c = ds.getConnection();
                     PreparedStatement read = c.prepareStatement("SELECT coins FROM bench_conc WHERE id = ?");
                     PreparedStatement update = c.prepareStatement("UPDATE bench_conc SET coins = ? WHERE id = ?")) {
                    read.setLong(1, row);
                    long coins;
                    try (ResultSet r = read.executeQuery()) {
                        r.next();
                        coins = r.getLong(1);
                    }
                    update.setLong(1, coins + 1);
                    update.setLong(2, row);
                    update.executeUpdate();
                }
            }
            default -> {
                try (Connection c = ds.getConnection();
                     PreparedStatement ps = c.prepareStatement("UPDATE bench_conc SET coins = coins + 1 WHERE id = ?")) {
                    ps.setLong(1, row);
                    ps.executeUpdate();
                }
            }
        }
        done.increment();
    }
}
