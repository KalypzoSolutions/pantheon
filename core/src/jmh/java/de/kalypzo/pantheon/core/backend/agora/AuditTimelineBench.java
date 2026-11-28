package de.kalypzo.pantheon.core.backend.agora;

import de.kalypzo.pantheon.core.audit.TimelineResult;
import de.kalypzo.pantheon.core.audit.TimelineSpec;
import de.kalypzo.pantheon.core.audit.Viewer;
import de.kalypzo.pantheon.core.backend.Backend;
import java.util.List;
import java.util.UUID;
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

/**
 * S6: the newest page (100 entries) of a player with 10k or 100k entries, as {@code /timeline} reads it; once with
 * planner statistics ({@code ANALYZE}, the steady state) and once right after the inserts, before autoanalyze ran.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class AuditTimelineBench {

    @Param({"10000", "100000"})
    public int entries;

    @Param({"analyzed", "fresh"})
    public String statistics;

    private AgoraContractFixture fx;
    private Backend node;
    private final UUID player = UUID.randomUUID();
    private final Viewer viewer = new Viewer(UUID.randomUUID(), "bench", "bench");

    @Setup(Level.Trial)
    public void setUp() {
        fx = new AgoraContractFixture(16);
        node = fx.node("lobby-01");
        for (int i = 0; i < entries / 1000; i++) Bench.get(node.append(AuditBench.bundle(player, 1000)));
        AuditBench.verifyStored(player, entries, "timeline seed " + entries);
        if (statistics.equals("analyzed")) {
            try (var ds = Bench.pool(1)) {
                Bench.sql(ds, "ANALYZE pantheon_audit");
            }
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        fx.close();
    }

    @Benchmark
    public TimelineResult newestPage() {
        return Bench.get(node.timeline(new TimelineSpec(player, null, null, List.of(), 100, null, null, viewer)));
    }
}
