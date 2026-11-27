package de.kalypzo.pantheon.core.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.outbox.Outbox;
import de.kalypzo.pantheon.core.outbox.OutboxDrainer;
import de.kalypzo.pantheon.core.player.PlayerDataEngine;
import de.kalypzo.pantheon.core.store.StoreRegistry;
import de.kalypzo.pantheon.data.CachedStore;
import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.data.PlayerFields;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reconnect sync (P-22; agora.md §3 Zustellung, §8, E7, E23). */
class ReconnectSyncTest {

    private static final DataNamespace ECONOMY = DataNamespace.of("economy");
    private static final DataNamespace LOBBY = DataNamespace.of("lobby");
    private static final Field<Long> COINS = ECONOMY.ofLong("coins", 0L);
    private static final Field<Long> MONEY = LOBBY.ofLong("money", 0L);
    private static final Field<Long> SCORE = LOBBY.ofLong("score", 0L);
    private static final Duration WAIT = Duration.ofSeconds(10);

    /** One node with the whole data graph; its backend is a spy that records every call. */
    private final class Node implements AutoCloseable {
        final String id;
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<DocRef> loads = new CopyOnWriteArrayList<>();
        /** For each {@code versions} call: was the outbox empty at that moment? */
        final List<Boolean> outboxEmptyAtVersions = new CopyOnWriteArrayList<>();
        final Backend backend;
        final Outbox outbox;
        final DocWriter writer;
        final PlayerDataEngine engine;
        final StoreRegistry stores;
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        final OutboxDrainer drainer;
        final ReconnectSync sync;

        Node(String id) {
            this.id = id;
            Logger log = Logger.getLogger("ReconnectSyncTest." + id);
            outbox = Outbox.open(dir.resolve(id + "-outbox.db"), Clock.systemUTC(), log);
            backend = spy(network.node(id));
            writer = new DocWriter(backend, outbox, Clock.systemUTC(), Runnable::run, log);
            engine = new PlayerDataEngine(backend, writer, Runnable::run, log);
            stores = new StoreRegistry(backend, writer, Runnable::run, log);
            drainer = new OutboxDrainer(outbox, backend, writer, Clock.systemUTC(), scheduler, log);
            sync = new ReconnectSync(backend, engine, stores, drainer);
            backend.start(sync);
        }

        private Backend spy(Backend real) {
            return (Backend) Proxy.newProxyInstance(Backend.class.getClassLoader(), new Class<?>[] {Backend.class},
                    (proxy, method, args) -> {
                        calls.add(method.getName());
                        if (method.getName().equals("load")) loads.add((DocRef) args[0]);
                        if (method.getName().equals("versions")) {
                            outboxEmptyAtVersions.add(outbox.pendingByDoc().isEmpty());
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        Doc load(UUID player) throws Exception {
            return engine.load(player, "p" + player.toString().substring(0, 4), Duration.ofSeconds(5))
                    .get(5, TimeUnit.SECONDS);
        }

        long playerLoads(UUID player) {
            return loads.stream().filter(new PlayerDoc(player)::equals).count();
        }

        @Override
        public void close() {
            sync.close();
            drainer.close();
            stores.close();
            engine.close();
            scheduler.shutdownNow();
            outbox.close();
        }
    }

    @TempDir
    Path dir;

    private InMemoryNetwork network;
    private final List<AutoCloseable> closeables = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        network = new InMemoryNetwork();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : closeables) c.close();
        network.close();
    }

    private Node node(String id) {
        Node n = new Node(id);
        closeables.add(n);
        return n;
    }

    /** Writes through {@code via} and waits until the database has it. */
    private static void write(Node via, UUID player, Field<Long> field, long value) {
        via.engine.remote(player).set(field, value);
        await().atMost(WAIT).until(() -> via.writer.idle(new PlayerDoc(player)));
    }

    private long stored(UUID player, String namespace, String name) throws Exception {
        for (Row r : network.node("reader-" + UUID.randomUUID()).load(new PlayerDoc(player)).get(5, TimeUnit.SECONDS)) {
            if (((PlayerRow) r.ref()).namespace().equals(namespace)) {
                Object v = r.data().get(name);
                return v == null ? 0 : ((Number) v).longValue();
            }
        }
        return 0;
    }

    // ---------- tests ----------

    @Test
    void changedRowsAreReloadedAfterReconnect() throws Exception {
        Node a = node("a");
        Node b = node("b");
        UUID p = UUID.randomUUID();
        write(b, p, COINS, 10L);
        Doc profile = a.load(p);
        assertThat(profile.get(COINS)).isEqualTo(10L);

        network.outage("a");
        write(b, p, COINS, 50L); // a misses the DocChanged
        assertThat(profile.get(COINS)).isEqualTo(10L);
        network.restore("a");

        await().atMost(WAIT).until(() -> profile.get(COINS) == 50L);
    }

    @Test
    void newNamespaceRowCreatedDuringOutageIsPicked() throws Exception {
        Node a = node("a");
        Node b = node("b");
        UUID p = UUID.randomUUID();
        write(b, p, COINS, 10L);
        Doc profile = a.load(p);

        network.outage("a");
        write(b, p, MONEY, 7L); // the lobby row did not exist before (E7)
        network.restore("a");

        await().atMost(WAIT).until(() -> profile.get(MONEY) == 7L);
        assertThat(profile.get(COINS)).isEqualTo(10L);
    }

    @Test
    void unchangedPlayersAreNotReloaded() throws Exception {
        Node a = node("a");
        Node b = node("b");
        UUID changed = UUID.randomUUID();
        UUID unchanged = UUID.randomUUID();
        write(b, changed, COINS, 1L);
        write(b, unchanged, COINS, 2L);
        a.load(changed);
        Doc other = a.load(unchanged);
        a.loads.clear();

        network.outage("a");
        write(b, changed, COINS, 3L);
        network.restore("a");

        await().atMost(WAIT).until(() -> a.engine.profile(changed).orElseThrow().get(COINS) == 3L);
        a.sync.sync().get(WAIT.toSeconds(), TimeUnit.SECONDS); // runs after the reconnect's sync: that one is done
        assertThat(a.playerLoads(changed)).isEqualTo(1);
        assertThat(a.playerLoads(unchanged)).isZero();
        assertThat(other.get(COINS)).isEqualTo(2L);
    }

    @Test
    void outboxDrainsBeforeVersionCheck() throws Exception {
        Node a = node("a");
        Node b = node("b");
        UUID p = UUID.randomUUID();
        write(b, p, COINS, 10L);
        Doc profile = a.load(p);

        network.outage("a");
        profile.inc(COINS, 5); // fails, goes to the outbox; pending locally
        await().atMost(WAIT).until(() -> !a.outbox.pendingByDoc().isEmpty());
        b.engine.remote(p).inc(COINS, 100);
        await().atMost(WAIT).until(() -> b.writer.idle(new PlayerDoc(p)));
        assertThat(profile.get(COINS)).isEqualTo(15L);
        a.outboxEmptyAtVersions.clear();
        network.restore("a");

        await().atMost(WAIT).until(() -> a.outbox.pendingByDoc().isEmpty() && stored(p, "economy", "coins") == 115L);
        await().atMost(WAIT).until(() -> profile.get(COINS) == 115L);
        a.sync.sync().get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(profile.get(COINS)).isEqualTo(115L);
        assertThat(a.outboxEmptyAtVersions).isNotEmpty().containsOnly(true);
    }

    @Test
    void cachedStoreFullyReloaded() throws Exception {
        Node a = node("a");
        Node b = node("b");
        assertThat(b.stores.store(LOBBY, "clans").create("red", d -> d.set(SCORE, 1L)).get(5, TimeUnit.SECONDS))
                .isTrue();
        assertThat(b.stores.store(LOBBY, "clans").create("blue", d -> d.set(SCORE, 2L)).get(5, TimeUnit.SECONDS))
                .isTrue();
        CachedStore cache = a.stores.store(LOBBY, "clans").cached();
        assertThat(cache.get("red")).isPresent();
        assertThat(cache.get("blue")).isPresent();

        network.outage("a");
        assertThat(b.stores.store(LOBBY, "clans").delete("red").get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(b.stores.store(LOBBY, "clans").create("green", d -> d.set(SCORE, 3L)).get(5, TimeUnit.SECONDS))
                .isTrue();
        assertThat(cache.get("red")).isPresent(); // the delete was missed
        network.restore("a");

        await().atMost(WAIT).until(() -> cache.get("red").isEmpty() && cache.get("green").isPresent());
        assertThat(cache.get("blue")).isPresent();
    }

    @Test
    void nodeStateReportsHeldPlayersAndWatchedStores() throws Exception {
        Node a = node("a");
        UUID p = UUID.randomUUID();
        assertThat(a.sync.heldPlayers()).isEmpty();
        assertThat(a.sync.watchedStores()).isEmpty();

        a.load(p);
        CachedStore cache = a.stores.store(LOBBY, "clans").cached();

        assertThat(a.sync.heldPlayers()).containsExactly(p);
        assertThat(a.sync.watchedStores()).containsExactly(new StoreRef("lobby", "clans"));
        assertThat(a.sync.heldPlayers()).containsExactlyElementsOf(a.engine.heldPlayers());
        assertThat(a.sync.watchedStores()).containsExactlyElementsOf(a.stores.watchedStores());
        assertThat(a.engine.profile(p).orElseThrow().get(PlayerFields.NAME)).startsWith("p");
        assertThat(cache.all()).isEmpty();
    }
}
