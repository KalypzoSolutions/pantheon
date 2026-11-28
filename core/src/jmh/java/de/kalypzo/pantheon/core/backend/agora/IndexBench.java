package de.kalypzo.pantheon.core.backend.agora;

import com.zaxxer.hikari.HikariDataSource;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.data.wire.DocSnapshot;
import java.util.List;
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
import org.openjdk.jmh.infra.BenchmarkParams;

/** S3: top 10 of N with and without a declared index, and ascending (not index-backed per data.md §4). */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class IndexBench {

    @Param({"1000"})
    public long n;

    @Param({"players", "store"})
    public String target;

    private AgoraContractFixture fx;
    private Backend node;
    private HikariDataSource ds;

    @Setup(Level.Trial)
    public void setUp(BenchmarkParams params) {
        fx = new AgoraContractFixture(16);
        node = fx.node("lobby-01");
        ds = Bench.pool(2);
        Ranking.ensureSeeded(ds, target, n);
        String index = Ranking.declareIndex(node, ds, target);
        probe(params.getBenchmark().substring(params.getBenchmark().lastIndexOf('.') + 1));
        if (!params.getBenchmark().endsWith(".topDescIndexed")) return; // check once per (n, target)
        for (boolean desc : new boolean[] {true, false}) {
            String plan = Ranking.plan(ds, target, index, desc);
            Bench.record("index-usage.jsonl", String.format(
                    "{\"target\": \"%s\", \"n\": %d, \"order\": \"%s\", \"index\": \"%s\", \"plan\": \"%s\"}",
                    target, n, desc ? "desc" : "asc", index, plan));
            System.err.println("[bench] " + target + " n=" + n + (desc ? " DESC" : " ASC") + ": index " + plan);
        }
    }

    /** One query up front: if it fails (Agora's query timeout is 5 s), that is a limit, recorded before the fork dies. */
    private void probe(String method) {
        long t0 = System.nanoTime();
        try {
            switch (method) {
                case "topDescIndexed" -> topDescIndexed();
                case "topDescNoIndex" -> topDescNoIndex();
                default -> topAscIndexed();
            }
        } catch (RuntimeException e) {
            Bench.record("index-limits.jsonl", String.format(
                    "{\"target\": \"%s\", \"n\": %d, \"method\": \"%s\", \"afterMs\": %d, \"error\": \"%s\"}",
                    target, n, method, (System.nanoTime() - t0) / 1_000_000,
                    String.valueOf(e.getCause() != null ? e.getCause() : e).replace("\"", "'")));
            throw e;
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        ds.close();
        fx.close();
    }

    @Benchmark
    public List<DocSnapshot> topDescIndexed() {
        return Ranking.top10(node, target, true, true);
    }

    @Benchmark
    public List<DocSnapshot> topDescNoIndex() {
        return Ranking.top10(node, target, false, true);
    }

    @Benchmark
    public List<DocSnapshot> topAscIndexed() {
        return Ranking.top10(node, target, true, false);
    }
}
