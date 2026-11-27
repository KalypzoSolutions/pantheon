package de.kalypzo.pantheon.core.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.NodeState;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.outbox.Outbox;
import de.kalypzo.pantheon.data.DataUnavailableException;
import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.data.PlayerFields;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Login, join and quit of a player on this node (P-24; player.md §7, §9, E25, Review-Fokus 1). */
class LoginCoordinatorTest {

    private static final DataNamespace ECONOMY = DataNamespace.of("economy");
    private static final Field<Long> COINS = ECONOMY.ofLong("coins", 0L);
    private static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration JOIN_GRACE = Duration.ofMillis(200);

    @TempDir
    Path dir;

    private final Logger log = Logger.getLogger("LoginCoordinatorTest");
    private final UUID player = UUID.randomUUID();
    /** Every {@code releasePlayer} this node sent. */
    private final List<UUID> releases = new CopyOnWriteArrayList<>();
    /** While not null, {@code apply} waits for it. */
    private volatile CompletableFuture<Void> applyGate;

    private InMemoryNetwork network;
    private Outbox outbox;
    private DocWriter writer;
    private PlayerDataEngine engine;
    private ScheduledExecutorService scheduler;
    private LoginCoordinator coordinator;

    @BeforeEach
    void setUp() {
        network = new InMemoryNetwork();
        Backend backend = recorded(network.node("lobby-1"));
        outbox = Outbox.open(dir.resolve("outbox.db"), Clock.systemUTC(), log);
        writer = new DocWriter(backend, outbox, Clock.systemUTC(), Runnable::run, log);
        engine = new PlayerDataEngine(backend, writer, Runnable::run, log);
        backend.start(new NodeState() {
            @Override
            public Collection<UUID> heldPlayers() {
                return engine.heldPlayers();
            }

            @Override
            public Collection<StoreRef> watchedStores() {
                return List.of();
            }
        });
        scheduler = Executors.newSingleThreadScheduledExecutor();
        coordinator = new LoginCoordinator(engine, scheduler, LOGIN_TIMEOUT, JOIN_GRACE);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        engine.close();
        outbox.close();
        network.close();
    }

    @Test
    void failedLoadDeniesLogin() throws Exception {
        network.outage(); // Agora not reachable

        LoginOutcome outcome = preLogin();

        assertThat(outcome).isInstanceOf(LoginOutcome.Denied.class);
        assertThat(((LoginOutcome.Denied) outcome).cause()).isInstanceOf(DataUnavailableException.class);
        assertThat(engine.profile(player)).isEmpty(); // never let in with empty data
        assertThat(engine.heldPlayers()).doesNotContain(player);
    }

    @Test
    void abortedLoginReleasesHolder() throws Exception {
        LoginOutcome outcome = preLogin();
        assertThat(outcome).isInstanceOf(LoginOutcome.Allowed.class);
        assertThat(engine.heldPlayers()).contains(player);
        await().atMost(Duration.ofSeconds(5)).until(() -> writer.idle(new PlayerDoc(player)));
        assertThat(storedName()).isEqualTo("Notch"); // E25: the pre-login writes pantheon.name

        coordinator.loginAborted(player); // PlayerLoginEvent not ALLOWED

        assertThat(engine.profile(player)).isEmpty();
        assertThat(engine.heldPlayers()).doesNotContain(player);
        assertThat(releases).containsExactly(player);
        Thread.sleep(JOIN_GRACE.toMillis() * 2); // the grace timer is gone: no second release
        assertThat(releases).containsExactly(player);
    }

    @Test
    void noJoinWithin30sReleases() throws Exception {
        assertThat(preLogin()).isInstanceOf(LoginOutcome.Allowed.class);

        // the client disconnects before the join: neither loginAborted nor joined is called
        await().atMost(Duration.ofSeconds(5)).until(() -> !engine.heldPlayers().contains(player));
        assertThat(engine.profile(player)).isEmpty();
        assertThat(releases).containsExactly(player);
    }

    @Test
    void joinCancelsPendingRelease() throws Exception {
        assertThat(preLogin()).isInstanceOf(LoginOutcome.Allowed.class);

        coordinator.joined(player);
        Thread.sleep(JOIN_GRACE.toMillis() * 3);

        assertThat(engine.profile(player)).isPresent();
        assertThat(engine.heldPlayers()).contains(player);
        assertThat(releases).isEmpty();
    }

    @Test
    void quitReleasesAndKeepsWritesRunning() throws Exception {
        Doc doc = ((LoginOutcome.Allowed) preLogin()).doc();
        coordinator.joined(player);
        await().atMost(Duration.ofSeconds(5)).until(() -> writer.idle(new PlayerDoc(player)));

        CompletableFuture<Void> gate = new CompletableFuture<>();
        applyGate = gate;
        doc.set(COINS, 7L); // still on its way when the player quits

        coordinator.quit(player);

        assertThat(engine.profile(player)).isEmpty();
        assertThat(engine.heldPlayers()).doesNotContain(player);
        assertThat(releases).containsExactly(player);
        assertThat(writer.idle(new PlayerDoc(player))).isFalse(); // the write is still running

        applyGate = null;
        gate.complete(null);
        await().atMost(Duration.ofSeconds(5)).until(() -> stored(COINS) == 7L);
    }

    @Test
    void changesUntilQuitAreSaved() throws Exception {
        Doc doc = ((LoginOutcome.Allowed) preLogin()).doc();
        coordinator.joined(player);

        doc.inc(COINS, 3L); // PlayerQuitEvent, priority HIGHEST
        doc.inc(COINS, 4L);
        coordinator.quit(player); // MONITOR

        await().atMost(Duration.ofSeconds(5)).until(() -> stored(COINS) == 7L);
        assertThat(engine.profile(player)).isEmpty();
    }

    @Test
    void refusedSecondLoginKeepsTheJoinedSession() throws Exception {
        assertThat(preLogin()).isInstanceOf(LoginOutcome.Allowed.class);
        coordinator.joined(player);

        assertThat(preLogin()).isInstanceOf(LoginOutcome.Allowed.class); // a second connection of the same player
        coordinator.loginAborted(player);
        Thread.sleep(JOIN_GRACE.toMillis() * 2);

        assertThat(engine.profile(player)).isPresent();
        assertThat(releases).isEmpty();
    }

    @Test
    void quitOfOldConnectionKeepsDataForNewerLogin() throws Exception {
        assertThat(preLogin()).isInstanceOf(LoginOutcome.Allowed.class);
        coordinator.joined(player);
        assertThat(preLogin()).isInstanceOf(LoginOutcome.Allowed.class); // the new connection kicks the old one

        coordinator.quit(player); // the old connection
        coordinator.joined(player); // the new one

        assertThat(engine.profile(player)).isPresent();
        assertThat(releases).isEmpty();
        coordinator.quit(player);
        assertThat(releases).containsExactly(player);
    }

    // ---------- helpers ----------

    private LoginOutcome preLogin() throws Exception {
        return coordinator.preLogin(player, "Notch")
                .get(LOGIN_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
    }

    private List<Row> rows() throws Exception {
        return network.node("reader-" + UUID.randomUUID()).load(new PlayerDoc(player)).get(5, TimeUnit.SECONDS);
    }

    private long stored(Field<Long> field) throws Exception {
        for (Row r : rows()) {
            if (((PlayerRow) r.ref()).namespace().equals(field.namespace().name())) {
                Object v = r.data().get(field.name());
                return v == null ? 0 : ((Number) v).longValue();
            }
        }
        return 0;
    }

    private Object storedName() throws Exception {
        for (Row r : rows()) {
            if (((PlayerRow) r.ref()).namespace().equals(PlayerFields.PANTHEON.name())) {
                return r.data().get(PlayerFields.NAME.name());
            }
        }
        return null;
    }

    /** Records {@code releasePlayer}, holds {@code apply} back while {@link #applyGate} is set. */
    private Backend recorded(Backend real) {
        return (Backend) Proxy.newProxyInstance(Backend.class.getClassLoader(), new Class<?>[] {Backend.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("releasePlayer")) releases.add((UUID) args[0]);
                    CompletableFuture<Void> gate = applyGate;
                    if (gate != null && method.getName().equals("apply")) {
                        return gate.thenCompose(v -> {
                            try {
                                return (CompletableFuture<?>) invoke(real, method, args);
                            } catch (Throwable e) {
                                return CompletableFuture.failedFuture(e);
                            }
                        });
                    }
                    return invoke(real, method, args);
                });
    }

    private static Object invoke(Object real, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
