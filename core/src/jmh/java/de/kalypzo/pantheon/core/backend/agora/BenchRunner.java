package de.kalypzo.pantheon.core.backend.agora;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs every suite once against one Postgres container shared by all JMH forks, writes {@code <name>.json} per run
 * plus {@code env.json} and the self-check lines ({@code *.jsonl}) to {@code bench.out}. Argument: a regex on the run
 * names (default: all). {@code -Dbench.quick=true}: one short iteration, N at most 10k.
 */
public final class BenchRunner {

    private static final List<String> N_FULL = List.of("1000", "10000", "100000", "1000000");
    private static final List<String> N_QUICK = List.of("1000", "10000");

    private final Pattern filter;
    private final boolean quick;
    private final Path out;
    private final String[] jvmArgs;
    private final List<String> failed = new ArrayList<>();

    private BenchRunner(Pattern filter, boolean quick, Path out, PostgreSQLContainer pg) {
        this.filter = filter;
        this.quick = quick;
        this.out = out;
        this.jvmArgs = new String[] {"-Xmx2g", "-Dbench.out=" + out, "-Dbench.quick=" + quick,
                "-Dbench.pg.url=" + pg.getJdbcUrl(), "-Dbench.pg.user=" + pg.getUsername(),
                "-Dbench.pg.password=" + pg.getPassword()};
    }

    public static void main(String[] args) throws Exception {
        Pattern filter = Pattern.compile(args.length > 0 ? args[0] : ".*");
        boolean quick = Boolean.getBoolean("bench.quick");
        Path out = Path.of(System.getProperty("bench.out", "build/results/jmh"));
        Files.createDirectories(out);
        boolean full = args.length == 0 || args[0].equals(".*");
        if (full) { // a filtered rerun replaces only its own files and keeps the full run's env.json
            try (var old = Files.list(out)) {
                for (Path p : old.toList()) Files.delete(p);
            }
        }
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:17-alpine")) {
            pg.start();
            if (full || !Files.exists(out.resolve("env.json"))) writeEnv(out, pg, quick);
            new BenchRunner(filter, quick, out, pg).runAll();
        }
    }

    private void runAll() {
        List<String> ns = quick ? N_QUICK : N_FULL;
        // S3 first and by growing N: the seed only grows, and ranking queries see every player row.
        // a ranking that hits Agora's query timeout is a result (index-limits.jsonl), not a reason to stop the run
        for (String n : ns) run(IndexBench.class, "IndexBench-n" + n, o -> o.param("n", n).shouldFailOnError(false));
        run(JdbcIndexBench.class, "JdbcIndexBench", o -> o.param("n", ns.toArray(String[]::new)));
        run(IndexWriteBench.class, "IndexWriteBench", o -> o);
        run(MainThreadBench.class, "MainThreadBench", o -> o);
        run(OpLatencyBench.class, "OpLatencyBench", o -> o);
        run(PropagationBench.class, "PropagationBench", o -> o);
        run(AuditBench.class, "AuditBench", o -> o);
        // forks share the database: every "fresh" fork must run before the first ANALYZE of an "analyzed" one
        run(AuditTimelineBench.class, "AuditTimelineBench-fresh", o -> o.param("statistics", "fresh"));
        run(AuditTimelineBench.class, "AuditTimelineBench-analyzed", o -> o.param("statistics", "analyzed"));
        run(MessagingBench.class, "MessagingBench", o -> o);
        // JMH has no thread @Param: one run per thread count
        for (int t : new int[] {1, 4, 16, 64}) {
            run(ConcurrencyBench.class, "ConcurrencyBench-t" + t, o -> o.threads(t));
        }
        run(ConcurrencyBench.class, "ConcurrencyPool", o -> o.threads(64).param("pool", "4", "16", "32")
                .param("rows", "spread").param("impl", "pantheon", "jdbcAtomic"));
        if (!failed.isEmpty()) throw new IllegalStateException("failed runs: " + failed);
    }

    private void run(Class<?> bench, String name, UnaryOperator<ChainedOptionsBuilder> extra) {
        if (!filter.matcher(name).find()) return;
        ChainedOptionsBuilder o = new OptionsBuilder()
                .include("\\." + bench.getSimpleName() + "\\.")
                .forks(1)
                .warmupIterations(1)
                .warmupTime(TimeValue.seconds(quick ? 1 : 3))
                .measurementIterations(quick ? 1 : 3)
                .measurementTime(TimeValue.seconds(quick ? 1 : 3))
                .jvmArgsAppend(jvmArgs)
                .shouldFailOnError(true)
                .resultFormat(ResultFormatType.JSON)
                .result(out.resolve(name + ".json").toString());
        String prof = System.getProperty("bench.prof", "");
        if (!prof.isEmpty()) { // e.g. stack, gc, jfr:configName=profile: why the numbers are what they are
            int colon = prof.indexOf(':');
            if (colon < 0) o.addProfiler(prof);
            else o.addProfiler(prof.substring(0, colon), prof.substring(colon + 1));
        }
        try {
            var results = new Runner(extra.apply(o).build()).run();
            // without failOnError a broken fork only shows as a missing result
            if (results.isEmpty()) failed.add(name);
            System.err.println("[bench] " + name + ": " + results.size() + " results");
        } catch (Exception e) {
            System.err.println("[bench] " + name + " failed: " + e);
            failed.add(name);
        }
    }

    private static void writeEnv(Path out, PostgreSQLContainer pg, boolean quick) throws Exception {
        String cpu = Files.readAllLines(Path.of("/proc/cpuinfo")).stream().filter(l -> l.startsWith("model name"))
                .findFirst().map(l -> l.substring(l.indexOf(':') + 1).trim()).orElse("unknown");
        long memKb = Files.readAllLines(Path.of("/proc/meminfo")).stream().filter(l -> l.startsWith("MemTotal"))
                .findFirst().map(l -> Long.parseLong(l.replaceAll("\\D", ""))).orElse(0L);
        String postgres;
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             ResultSet r = c.createStatement().executeQuery("SHOW server_version")) {
            r.next();
            postgres = r.getString(1);
        }
        String json = String.format("""
                {"date": "%s", "commit": "%s", "quick": %s, "cpu": "%s", "cores": %d, "memGb": %d, \
                "java": "%s", "postgres": "%s", "agoraPool": 16}
                """, Instant.now(), System.getProperty("bench.commit", "?"), quick, cpu,
                Runtime.getRuntime().availableProcessors(), Math.round(memKb / 1024.0 / 1024.0),
                Runtime.version(), postgres);
        Files.writeString(out.resolve("env.json"), json);
    }
}
