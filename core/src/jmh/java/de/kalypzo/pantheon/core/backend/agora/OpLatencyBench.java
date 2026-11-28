package de.kalypzo.pantheon.core.backend.agora;

import static de.kalypzo.pantheon.core.backend.agora.Bench.COINS;
import static de.kalypzo.pantheon.core.backend.agora.Bench.get;
import static de.kalypzo.pantheon.core.backend.agora.Bench.inc;
import static de.kalypzo.pantheon.core.backend.agora.Bench.withdraw;
import static de.kalypzo.pantheon.core.backend.agora.Bench.write;

import com.zaxxer.hikari.HikariDataSource;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.data.codec.FieldSpecs;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.data.wire.TransferResult;
import de.kalypzo.pantheon.data.Doc;
import io.grpc.ManagedChannel;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * S2: round trip of one operation until the database confirmed it, Pantheon (node → gRPC → Agora → Postgres) against
 * the same thing written by hand over JDBC. {@code burst100}: 100 changes in a row, as a plugin makes them, until
 * all are in the database; Pantheon coalesces them, the baseline sends 100 statements.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class OpLatencyBench {

    private static final long RICH = 1_000_000_000_000L;

    private AgoraContractFixture fx;
    private Backend node;
    private Engine engine;
    private HikariDataSource ds;
    private ManagedChannel plain;
    private HealthGrpc.HealthBlockingStub health;
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID burst = UUID.randomUUID();
    private Doc burstDoc;
    private long burstIncs;
    private final StoreDoc storeDoc = new StoreDoc(new StoreRef("bench", "latency"), "d");

    @Setup(Level.Trial)
    public void setUp() {
        fx = new AgoraContractFixture(16);
        node = fx.node("lobby-01");
        engine = new Engine(node);
        get(node.apply(write(new PlayerDoc(a), inc(COINS, RICH))));
        get(node.apply(write(new PlayerDoc(b), inc(COINS, RICH))));
        get(node.create(Bench.key(), storeDoc, Map.of(COINS.name(), 0L)));
        burstDoc = engine.load(burst);
        plain = NettyChannelBuilder.forTarget("localhost:" + fx.port()).usePlaintext().build();
        health = HealthGrpc.newBlockingStub(plain);
        ds = Bench.pool(4);
        Bench.sql(ds, "CREATE TABLE IF NOT EXISTS bench_player (player uuid PRIMARY KEY, coins bigint NOT NULL)",
                "INSERT INTO bench_player VALUES ('" + a + "', " + RICH + "), ('" + b + "', " + RICH + ")");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        plain.shutdownNow();
        engine.close();
        if (burstIncs > 0) Bench.verifyCoins(burst, burstIncs, "pantheonBurst100");
        ds.close();
        fx.close();
    }

    // ----- Pantheon -----

    @Benchmark
    public ApplyResult pantheonInc() {
        return get(node.apply(write(new PlayerDoc(a), inc(COINS, 1))));
    }

    @Benchmark
    public ApplyResult pantheonWithdraw() {
        return get(node.apply(write(new PlayerDoc(a), withdraw(COINS, 1))));
    }

    @Benchmark
    public TransferResult pantheonTransfer() {
        return get(node.transfer(Bench.key(), new PlayerDoc(a), new PlayerDoc(b), FieldSpecs.of(COINS), 1));
    }

    @Benchmark
    public List<Row> pantheonLoad() {
        return get(node.load(new PlayerDoc(a)));
    }

    @Benchmark
    public List<Row> pantheonLoadPlayer() {
        return get(node.loadPlayer(a));
    }

    @Benchmark
    public List<Row> pantheonLoadStoreDoc() {
        return get(node.load(storeDoc));
    }

    @Benchmark
    public void pantheonBurst100() {
        for (int i = 0; i < 100; i++) burstDoc.inc(COINS, 1);
        burstIncs += 100;
        engine.awaitIdle(burst);
    }

    /** The transport floor: a unary gRPC call to the same Agora that touches no database. */
    @Benchmark
    public HealthCheckResponse grpcHealthCheck() {
        return health.check(HealthCheckRequest.getDefaultInstance());
    }

    // ----- JDBC baseline -----

    @Benchmark
    public int jdbcInc() throws SQLException {
        return update("UPDATE bench_player SET coins = coins + 1 WHERE player = ?");
    }

    @Benchmark
    public int jdbcWithdraw() throws SQLException {
        return update("UPDATE bench_player SET coins = coins - 1 WHERE player = ? AND coins >= 1");
    }

    @Benchmark
    public boolean jdbcTransfer() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement from = c.prepareStatement(
                         "UPDATE bench_player SET coins = coins - 1 WHERE player = ? AND coins >= 1");
                 PreparedStatement to = c.prepareStatement(
                         "UPDATE bench_player SET coins = coins + 1 WHERE player = ?")) {
                from.setObject(1, a);
                if (from.executeUpdate() == 0) {
                    c.rollback();
                    return false;
                }
                to.setObject(1, b);
                to.executeUpdate();
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    @Benchmark
    public long jdbcSelect() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT coins FROM bench_player WHERE player = ?")) {
            ps.setObject(1, a);
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    @Benchmark
    public void jdbcBurst100() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE bench_player SET coins = coins + 1 WHERE player = ?")) {
            ps.setObject(1, b);
            for (int i = 0; i < 100; i++) ps.executeUpdate();
        }
    }

    private int update(String sql) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, a);
            return ps.executeUpdate();
        }
    }
}
