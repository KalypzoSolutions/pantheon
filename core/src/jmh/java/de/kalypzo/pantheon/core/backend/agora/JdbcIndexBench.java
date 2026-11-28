package de.kalypzo.pantheon.core.backend.agora;

import static de.kalypzo.pantheon.core.backend.agora.Bench.SQL_PLAYER;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.infra.Blackhole;

/** S3 baseline: the same top 10 on a typed column with a plain B-tree index, straight over JDBC. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class JdbcIndexBench {

    @Param({"1000"})
    public long n;

    private HikariDataSource ds;

    @Setup(Level.Trial)
    public void setUp() {
        ds = Bench.pool(2);
        Bench.sql(ds, "CREATE TABLE IF NOT EXISTS bench_rank (player uuid PRIMARY KEY, coins bigint NOT NULL)",
                "CREATE INDEX IF NOT EXISTS bench_rank_coins ON bench_rank (coins DESC, player)");
        long have = Bench.scalar(ds, "SELECT count(*) FROM bench_rank");
        if (have < n) {
            Bench.sql(ds, "INSERT INTO bench_rank SELECT " + SQL_PLAYER + ", (i::bigint * 7919) % 1000003 "
                    + "FROM generate_series(" + (have + 1) + ", " + n + ") i", "ANALYZE bench_rank");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        ds.close();
    }

    @Benchmark
    public void topDesc(Blackhole bh) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player, coins FROM bench_rank ORDER BY coins DESC, player LIMIT 10");
             ResultSet r = ps.executeQuery()) {
            while (r.next()) {
                bh.consume(r.getObject(1));
                bh.consume(r.getLong(2));
            }
        }
    }
}
