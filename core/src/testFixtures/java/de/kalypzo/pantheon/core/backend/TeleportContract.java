package de.kalypzo.pantheon.core.backend;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.world.NetworkLocation;
import de.kalypzo.pantheon.world.TeleportResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The teleport contract ({@link PresenceBackend#teleport}): every implementation must pass it, the in-memory network
 * as well as a real Agora. {@code lobby-01} ({@code a}) calls, {@code lobby-02} ({@code b}) is the target and answers
 * with {@link #arrivals}. The proxy step goes through {@link ContractFixture#proxyConnects}; by default it switches
 * the player and reports the arrival on the target. Fixtures run with the {@code TELEPORT_*} limits of
 * {@link ContractFixture}. Callbacks may run synchronously on the calling thread, so tests set their answers before
 * they teleport and never block inside a callback. Every test uses fresh players.
 */
public abstract class TeleportContract {

    protected static final String PROXY = "proxy-01";

    private static final Duration WAIT = Duration.ofSeconds(10);

    private ContractFixture fx;
    private Backend a;
    private Backend b;
    private final RecordingArrivals arrivals = new RecordingArrivals();
    private final AtomicInteger connects = new AtomicInteger();
    private volatile ProxyConnector connector = this::arrive;

    /** A new, empty-enough network; called once per test and closed after it. */
    protected abstract ContractFixture fixture();

    @BeforeEach
    void setUpContract() {
        fx = fixture();
        a = fx.node("lobby-01");
        b = fx.node("lobby-02");
        b.answerArrivals(arrivals);
        fx.proxyConnects((player, server) -> {
            connects.incrementAndGet();
            return connector.connect(player, server);
        });
    }

    @AfterEach
    void tearDownContract() {
        fx.close();
    }

    @Test
    void teleportArrivesOk() throws Exception {
        UUID p = online("lobby-01");
        NetworkLocation to = at("lobby-02", 1);

        assertThat(result(a.teleport(p, "lobby-02", to))).isEqualTo(TeleportResult.OK);

        RecordingArrivals.Prepared prepared = arrivals.nextPrepared();
        assertThat(prepared.player()).isEqualTo(p);
        assertThat(prepared.location()).isEqualTo(to);
    }

    @Test
    void connectPreparesWithoutLocation() throws Exception {
        UUID p = online("lobby-01");

        assertThat(result(a.teleport(p, "lobby-02", null))).isEqualTo(TeleportResult.OK);

        assertThat(arrivals.nextPrepared().location()).isNull();
    }

    @Test
    void offlineIsNotOnline() throws Exception {
        assertThat(result(a.teleport(UUID.randomUUID(), "lobby-02", at("lobby-02", 1))))
                .isEqualTo(TeleportResult.NOT_ONLINE);
        assertThat(arrivals.prepared).isEmpty();
    }

    @Test
    void playerWithoutServerIsNotOnline() throws Exception {
        UUID p = UUID.randomUUID();
        fx.join(entry(p, null));
        await().atMost(WAIT).until(() -> a.snapshot().stream().anyMatch(e -> e.player().equals(p)
                && e.server() == null));

        assertThat(result(a.teleport(p, "lobby-02", at("lobby-02", 1)))).isEqualTo(TeleportResult.NOT_ONLINE);
        assertThat(arrivals.prepared).isEmpty();
    }

    @Test
    void unknownServerIsUnavailable() throws Exception {
        UUID p = online("lobby-01");

        assertThat(result(a.teleport(p, "lobby-09", at("lobby-09", 1))))
                .isEqualTo(TeleportResult.SERVER_UNAVAILABLE);
    }

    @Test
    void nodeWithoutHandlerIsUnavailable() throws Exception {
        fx.node("lobby-03");
        UUID p = online("lobby-01");

        assertThat(result(a.teleport(p, "lobby-03", at("lobby-03", 1))))
                .isEqualTo(TeleportResult.SERVER_UNAVAILABLE);
    }

    @Test
    void worldNotFound() throws Exception {
        UUID p = online("lobby-01");
        arrivals.answer = pr -> completedFuture(ArrivalStatus.WORLD_NOT_FOUND);

        assertThat(result(a.teleport(p, "lobby-02", at("lobby-02", 1)))).isEqualTo(TeleportResult.WORLD_NOT_FOUND);
        assertThat(connects).hasValue(0);
    }

    @Test
    void alreadyHereSkipsConnect() throws Exception {
        UUID p = online("lobby-02");
        CompletableFuture<ArrivalStatus> here = new CompletableFuture<>();
        arrivals.answer = pr -> here;

        CompletableFuture<TeleportResult> running = a.teleport(p, "lobby-02", at("lobby-02", 1));
        RecordingArrivals.Prepared prepared = arrivals.nextPrepared();
        here.complete(ArrivalStatus.ALREADY_HERE); // the answer first, then the report
        b.reportArrival(prepared.requestId(), true);

        assertThat(result(running)).isEqualTo(TeleportResult.OK);
        assertThat(connects).hasValue(0);
    }

    @Test
    void callerIsTarget() throws Exception {
        a.answerArrivals(arrivals);
        UUID p = online("lobby-02");
        NetworkLocation to = at("lobby-01", 1);

        assertThat(result(a.teleport(p, "lobby-01", to))).isEqualTo(TeleportResult.OK);
        assertThat(arrivals.nextPrepared().location()).isEqualTo(to);
    }

    @Test
    void proxyFailedIsConnectFailedAndCancels() throws Exception {
        UUID p = online("lobby-01");
        connector = (pl, s) -> completedFuture(ConnectStatus.FAILED);

        assertThat(result(a.teleport(p, "lobby-02", at("lobby-02", 1)))).isEqualTo(TeleportResult.CONNECT_FAILED);
        assertThat(arrivals.cancelled.poll(10, TimeUnit.SECONDS)).isEqualTo(arrivals.nextPrepared().requestId());
    }

    @Test
    void arrivalFailedIsConnectFailed() throws Exception {
        UUID p = online("lobby-01");
        connector = (pl, s) -> {
            fx.node(s).reportArrival(lastId(), false);
            return completedFuture(ConnectStatus.CONNECTED);
        };

        assertThat(result(a.teleport(p, "lobby-02", at("lobby-02", 1)))).isEqualTo(TeleportResult.CONNECT_FAILED);
    }

    @Test
    void playerNotFoundAtProxyIsNotOnline() throws Exception {
        UUID p = online("lobby-01");
        connector = (pl, s) -> completedFuture(ConnectStatus.PLAYER_NOT_FOUND);

        assertThat(result(a.teleport(p, "lobby-02", at("lobby-02", 1)))).isEqualTo(TeleportResult.NOT_ONLINE);
    }

    @Test
    void arrivalBeforeConnectResultIsOk() throws Exception {
        UUID p = online("lobby-01");
        CompletableFuture<ConnectStatus> pending = new CompletableFuture<>();
        connector = (pl, s) -> {
            arrive(pl, s);
            return pending;
        };

        assertThat(result(a.teleport(p, "lobby-02", at("lobby-02", 1)))).isEqualTo(TeleportResult.OK);
        assertThat(pending).isNotDone();
        pending.complete(ConnectStatus.CONNECTED);
    }

    @Test
    void alreadyConnectedWaitsForArrival() throws Exception {
        UUID p = online("lobby-02");
        AtomicReference<CompletableFuture<TeleportResult>> running = new AtomicReference<>();
        AtomicBoolean doneBeforeReport = new AtomicBoolean(true);
        connector = (pl, s) -> {
            String id = lastId();
            CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(() -> {
                doneBeforeReport.set(running.get().isDone());
                fx.node(s).reportArrival(id, true);
            });
            return completedFuture(ConnectStatus.ALREADY_CONNECTED);
        };

        running.set(a.teleport(p, "lobby-02", at("lobby-02", 1)));

        assertThat(result(running.get())).isEqualTo(TeleportResult.OK);
        assertThat(doneBeforeReport).isFalse();
    }

    @Test
    void noArrivalTimesOutAndCancels() throws Exception {
        UUID p = online("lobby-01");
        connector = (pl, s) -> completedFuture(ConnectStatus.CONNECTED);

        assertThat(result(a.teleport(p, "lobby-02", at("lobby-02", 1)))).isEqualTo(TeleportResult.TIMEOUT);
        assertThat(arrivals.cancelled.poll(10, TimeUnit.SECONDS)).isEqualTo(arrivals.nextPrepared().requestId());
    }

    @Test
    void unansweredPrepareTimesOutAndCancels() throws Exception {
        UUID p = online("lobby-01");
        arrivals.answer = pr -> new CompletableFuture<>();

        assertThat(result(a.teleport(p, "lobby-02", at("lobby-02", 1)))).isEqualTo(TeleportResult.TIMEOUT);
        assertThat(arrivals.cancelled.poll(10, TimeUnit.SECONDS)).isEqualTo(arrivals.nextPrepared().requestId());
        assertThat(connects).hasValue(0);
    }

    @Test
    void newerTeleportSupersedesAndCancels() throws Exception {
        UUID p = online("lobby-01");
        CompletableFuture<ConnectStatus> held = new CompletableFuture<>();
        AtomicBoolean first = new AtomicBoolean(true);
        connector = (pl, s) -> first.getAndSet(false) ? held : arrive(pl, s);
        NetworkLocation second = at("lobby-02", 2);

        CompletableFuture<TeleportResult> t1 = a.teleport(p, "lobby-02", at("lobby-02", 1));
        RecordingArrivals.Prepared p1 = arrivals.nextPrepared();
        await().atMost(WAIT).pollInterval(Duration.ofMillis(10)).until(() -> connects.get() == 1);
        CompletableFuture<TeleportResult> t2 = a.teleport(p, "lobby-02", second);

        assertThat(result(t1)).isEqualTo(TeleportResult.SUPERSEDED);
        // T2 waits for T1's open ConnectPlayer; that expires on its own after TELEPORT_CONNECT, so look only briefly
        assertThat(arrivals.prepared.poll(300, TimeUnit.MILLISECONDS)).isNull();
        held.complete(ConnectStatus.CONNECTED);
        assertThat(arrivals.cancelled.poll(10, TimeUnit.SECONDS)).isEqualTo(p1.requestId());

        assertThat(arrivals.nextPrepared().location()).isEqualTo(second);
        assertThat(result(t2)).isEqualTo(TeleportResult.OK);
    }

    @Test
    void presenceRemovalEndsTeleport() throws Exception {
        UUID p = online("lobby-01");
        connector = (pl, s) -> {
            fx.leave(pl);
            return new CompletableFuture<>();
        };

        assertThat(result(a.teleport(p, "lobby-02", at("lobby-02", 1)))).isEqualTo(TeleportResult.NOT_ONLINE);
    }

    @Test
    void targetGoneIsUnavailable() throws Exception {
        UUID p = online("lobby-01");
        arrivals.answer = pr -> new CompletableFuture<>();

        CompletableFuture<TeleportResult> running = a.teleport(p, "lobby-02", at("lobby-02", 1));
        arrivals.nextPrepared();
        fx.disconnect("lobby-02");

        assertThat(result(running)).isEqualTo(TeleportResult.SERVER_UNAVAILABLE);
    }

    @Test
    void outageIsNetworkUnavailable() throws Exception {
        UUID p = online("lobby-01");
        fx.outage();
        try {
            long start = System.nanoTime();
            TeleportResult r = result(a.teleport(p, "lobby-02", at("lobby-02", 1)));

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            assertThat(r).isEqualTo(TeleportResult.NETWORK_UNAVAILABLE);
        } finally {
            fx.restore();
        }
    }

    // ---------- helpers ----------

    /** The default proxy step: switch the player, then report its arrival on the target. */
    private CompletableFuture<ConnectStatus> arrive(UUID player, String server) {
        fx.switchServer(player, server);
        fx.node(server).reportArrival(lastId(), true);
        return completedFuture(ConnectStatus.CONNECTED);
    }

    private String lastId() {
        return Objects.requireNonNull(arrivals.last, "no PrepareArrival yet").requestId();
    }

    /** A fresh player, online on {@code server} as {@code a} sees it (Agora checks its own copy). */
    private UUID online(String server) {
        UUID p = UUID.randomUUID();
        fx.join(entry(p, null));
        fx.switchServer(p, server); // a claim carries no server (Agora takes it from the switch)
        await().atMost(WAIT).until(() -> a.snapshot().stream().anyMatch(e -> e.player().equals(p)
                && server.equals(e.server())));
        return p;
    }

    private static PresenceEntry entry(UUID player, @Nullable String server) {
        return new PresenceEntry(player, "P" + player.toString().substring(0, 8), server, PROXY, Locale.GERMANY,
                Instant.now());
    }

    private static NetworkLocation at(String server, double x) {
        return new NetworkLocation(server, "world", x, 64, -3, 90, 10);
    }

    private static TeleportResult result(CompletableFuture<TeleportResult> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }
}
