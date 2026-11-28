package de.kalypzo.pantheon.core.backend.agora;

import com.zaxxer.hikari.HikariDataSource;
import de.kalypzo.pantheon.core.audit.AuditBundle;
import de.kalypzo.pantheon.core.audit.AuditRecord;
import de.kalypzo.pantheon.core.audit.Position;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.data.UuidV7;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

/** S6: one bundle of audit entries until Agora confirmed it; entries per second = bundle / time per call. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class AuditBench {

    @Param({"1", "100", "1000"})
    public int bundle;

    private AgoraContractFixture fx;
    private Backend node;
    private final UUID player = UUID.randomUUID();
    private final LongAdder appended = new LongAdder();

    @Setup(Level.Trial)
    public void setUp() {
        fx = new AgoraContractFixture(16);
        node = fx.node("lobby-01");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        fx.close();
        verifyStored(player, appended.sum(), "bundle " + bundle);
    }

    @Benchmark
    public Void append() {
        Void v = Bench.get(node.append(bundle(player, bundle)));
        appended.add(bundle);
        return v;
    }

    /** Every confirmed entry has to be in {@code pantheon_audit}, or the entries/s figure means nothing. */
    static void verifyStored(UUID player, long confirmed, String what) {
        try (HikariDataSource ds = Bench.pool(1)) {
            long stored = Bench.scalar(ds, "SELECT count(*) FROM pantheon_audit WHERE player = '" + player
                    + "' AND action = 'bench:buy'");
            Bench.record("audit.jsonl", String.format("{\"what\": \"%s\", \"confirmed\": %d, \"stored\": %d}",
                    what, confirmed, stored));
            if (stored != confirmed) {
                throw new IllegalStateException(what + ": " + confirmed + " confirmed, " + stored + " stored");
            }
        }
    }

    /** {@code size} entries as a shop plugin writes them: action, position, one detail. */
    static AuditBundle bundle(UUID player, int size) {
        List<AuditRecord> records = new ArrayList<>(size);
        Position at = new Position("world", 12.5, 64, -3);
        for (int i = 0; i < size; i++) {
            records.add(new AuditRecord(UuidV7.next(Clock.systemUTC()), player, "bench:buy", at, null, null,
                    Map.of("item", "diamond_sword")));
        }
        return new AuditBundle(records, 0);
    }
}
