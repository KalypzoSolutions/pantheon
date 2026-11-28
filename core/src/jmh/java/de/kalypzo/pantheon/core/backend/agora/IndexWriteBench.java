package de.kalypzo.pantheon.core.backend.agora;

import com.zaxxer.hikari.HikariDataSource;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import java.util.concurrent.ThreadLocalRandom;
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

/** S3: what the index costs on every write. {@code inc} on random seeded rows, with and without the index. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class IndexWriteBench {

    @Param({"players", "store"})
    public String target;

    private AgoraContractFixture fx;
    private Backend node;
    private long rows;

    @Setup(Level.Trial)
    public void setUp() {
        fx = new AgoraContractFixture(16);
        node = fx.node("lobby-01");
        try (HikariDataSource ds = Bench.pool(2)) {
            rows = Math.min(Ranking.ensureSeeded(ds, target, 10_000), 100_000);
            Ranking.declareIndex(node, ds, target);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        fx.close();
    }

    @Benchmark
    public ApplyResult incIndexed() {
        return inc(true);
    }

    @Benchmark
    public ApplyResult incNoIndex() {
        return inc(false);
    }

    private ApplyResult inc(boolean indexed) {
        long i = 1 + ThreadLocalRandom.current().nextLong(rows);
        return Bench.get(node.apply(Bench.write(Ranking.doc(target, indexed, i),
                Bench.inc(indexed ? Ranking.IDX : Ranking.NOIDX, 1))));
    }
}
