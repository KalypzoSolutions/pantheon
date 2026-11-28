package de.kalypzo.pantheon.core.backend.agora;

import static de.kalypzo.pantheon.core.backend.agora.Bench.COINS;

import com.zaxxer.hikari.HikariDataSource;
import de.kalypzo.pantheon.data.Doc;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;

/**
 * S1: what the server thread pays per access. Pantheon reads from memory and only hands a write on (it is sent in
 * the background); the baseline blocks on the database for every access.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MainThreadBench {

    private AgoraContractFixture fx;
    private Engine engine;
    private Doc doc;
    private HikariDataSource ds;
    private final UUID player = UUID.randomUUID();
    private long incs; // one benchmark thread
    private String method = "";

    @Setup(Level.Trial)
    public void setUp(BenchmarkParams params) throws SQLException {
        fx = new AgoraContractFixture(16);
        engine = new Engine(fx.node("lobby-01"));
        doc = engine.load(player);
        ds = Bench.pool(4);
        Bench.sql(ds, "CREATE TABLE IF NOT EXISTS bench_player (player uuid PRIMARY KEY, coins bigint NOT NULL)",
                "INSERT INTO bench_player VALUES ('" + player + "', 0)");
        // a running server has a warm JIT; at one call per tick JMH's own warmup is far too short
        method = params.getBenchmark();
        for (int i = 0; i < Bench.warmup(); i++) {
            if (method.endsWith(".pantheonInc")) {
                doc.inc(COINS, 1);
                incs++;
                engine.awaitIdle(player);
            } else if (method.endsWith(".jdbcSelect")) {
                jdbcSelect(null);
            } else if (method.endsWith(".jdbcUpdate")) {
                jdbcUpdate(null);
            }
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        engine.close(); // flushes
        if (method.contains(".pantheonInc")) Bench.verifyCoins(player, incs, method);
        ds.close();
        fx.close();
    }

    @Benchmark
    public long pantheonGet() {
        return doc.get(COINS);
    }

    /**
     * Before every call: the doc has nothing unconfirmed, and the next tick has come (5 ms), so the ack and the echo
     * of the previous write are handled. The usual case of a few writes per tick; the JDBC baseline gets the same.
     */
    @State(Scope.Thread)
    public static class NextTick {
        @Setup(Level.Invocation)
        public void awaitIdle(MainThreadBench b) {
            b.engine.awaitIdle(b.player);
            LockSupport.parkNanos(5_000_000);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    public void pantheonInc(NextTick tick) {
        doc.inc(COINS, 1);
        incs++;
    }

    /**
     * {@code inc} in a tight loop on one doc: unconfirmed ops pile up while a request is in flight, and every call
     * copies and replays them (DocHandle.addPending), so the cost grows with their number.
     */
    @Benchmark
    public void pantheonIncFlood() {
        doc.inc(COINS, 1);
        incs++;
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    public long jdbcSelect(NextTick tick) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT coins FROM bench_player WHERE player = ?")) {
            ps.setObject(1, player);
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    public int jdbcUpdate(NextTick tick) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE bench_player SET coins = coins + 1 WHERE player = ?")) {
            ps.setObject(1, player);
            return ps.executeUpdate();
        }
    }
}
