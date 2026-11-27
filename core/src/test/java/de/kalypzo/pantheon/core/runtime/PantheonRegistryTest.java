package de.kalypzo.pantheon.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.core.backend.DataBackend;
import de.kalypzo.pantheon.core.backend.NodeState;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.outbox.Outbox;
import de.kalypzo.pantheon.core.player.PlayerDataEngine;
import de.kalypzo.pantheon.core.store.StoreRegistry;
import de.kalypzo.pantheon.data.CachedStore;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.FieldListener;
import de.kalypzo.pantheon.core.player.PlayerChangeListener;
import java.lang.ref.WeakReference;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.data.Store;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One scope per plugin, torn down on disable (P-23, megalodon §1, Review-Fokus 5). */
class PantheonRegistryTest {

    private static final DataNamespace LOBBY = DataNamespace.of("lobby");
    private static final Field<Long> COINS = LOBBY.ofLong("coins", 0L);

    @TempDir
    Path dir;

    private final Logger log = Logger.getLogger("PantheonRegistryTest");
    /** The "main thread": listener tasks wait here until the test runs them. */
    private final Queue<Runnable> mainThread = new ConcurrentLinkedQueue<>();
    private final Executor main = mainThread::add;
    /** apply() calls held back by the gate; completed when the test opens it. */
    private final List<Runnable> gated = new CopyOnWriteArrayList<>();
    private volatile boolean gateClosed;

    private InMemoryNetwork.Node backend;
    private Outbox outbox;
    private DocWriter writer;
    private StoreRegistry stores;
    private PlayerDataEngine engine;
    private final PantheonRegistry<String, PluginScope> registry = new PantheonRegistry<>();

    @BeforeEach
    void setUp() {
        backend = InMemoryNetwork.single();
        outbox = Outbox.open(dir.resolve("outbox.db"), Clock.systemUTC(), log);
        writer = new DocWriter(gate(backend), backend::state, outbox, Clock.systemUTC(), Runnable::run, log);
        stores = new StoreRegistry(backend, writer, main, log);
        engine = new PlayerDataEngine(backend, writer, main, log);
        backend.start(new NodeState() {
            @Override
            public Collection<UUID> heldPlayers() {
                return engine.heldPlayers();
            }

            @Override
            public Collection<StoreRef> watchedStores() {
                return stores.watchedStores();
            }
        });
    }

    @AfterEach
    void tearDown() {
        registry.closeAll();
        engine.close();
        stores.close();
        outbox.close();
        backend.close();
    }

    @Test
    void sameInstancePerKey() {
        AtomicInteger created = new AtomicInteger();
        PluginScope first = registry.get("lobby", k -> {
            created.incrementAndGet();
            return new PluginScope();
        });
        PluginScope second = registry.get("lobby", k -> {
            created.incrementAndGet();
            return new PluginScope();
        });
        assertThat(second).isSameAs(first);
        assertThat(created).hasValue(1);
    }

    @Test
    void differentKeysDifferentInstances() {
        PluginScope lobby = registry.get("lobby", k -> new PluginScope());
        PluginScope shop = registry.get("shop", k -> new PluginScope());
        assertThat(shop).isNotSameAs(lobby);

        registry.close("lobby");
        assertThat(lobby.isClosed()).isTrue();
        assertThat(shop.isClosed()).isFalse();
        // after a disable the plugin gets a fresh instance on its next enable
        assertThat(registry.get("lobby", k -> new PluginScope())).isNotSameAs(lobby);
    }

    @Test
    void disableTearsDownSubscriptions() throws Exception {
        PluginScope scope = registry.get("lobby", k -> new PluginScope());
        Store clans = scope.store(stores.store(LOBBY, "clans"), stores);
        List<Long> storeEvents = new CopyOnWriteArrayList<>();
        clans.onChange(COINS, (doc, before, after) -> storeEvents.add(after));

        UUID player = UUID.randomUUID();
        engine.load(player, "Steve", Duration.ofSeconds(5)).get();
        List<Long> playerEvents = new CopyOnWriteArrayList<>();
        scope.onPlayerChange(engine, COINS, (uuid, before, after) -> playerEvents.add(after));

        // listeners work while the plugin is enabled
        clans.doc("c1").inc(COINS, 1);
        engine.remote(player).inc(COINS, 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            runMainThread();
            return storeEvents.size() == 1 && playerEvents.size() == 1;
        });
        assertThat(storeEvents).containsExactly(1L);
        assertThat(playerEvents).containsExactly(1L);
        assertThat(stores.watchedStores()).contains(new StoreRef("lobby", "clans"));

        // a write is running (its apply has not been answered) and its listener task waits on the main thread
        gateClosed = true;
        clans.doc("c1").inc(COINS, 5);
        engine.remote(player).inc(COINS, 5);
        assertThat(gated).hasSize(2);
        assertThat(mainThread).as("listener task of the running write, queued before the disable").isNotEmpty();

        registry.close("lobby"); // PluginDisableEvent

        runMainThread();          // the queued listener tasks of the disabled plugin
        openGate();               // the running writes finish
        awaitWritesDone(player);
        clans.doc("c1").inc(COINS, 1);   // and a later write (another plugin) fires nothing either
        engine.remote(player).inc(COINS, 1);
        awaitWritesDone(player);
        Thread.sleep(200); // change events of the network arrive on its own thread
        runMainThread();

        assertThat(storeEvents).containsExactly(1L);
        assertThat(playerEvents).containsExactly(1L);
        assertThat(stores.watchedStores()).doesNotContain(new StoreRef("lobby", "clans"));
        // the running writes still arrived
        Store fresh = stores.store(LOBBY, "clans");
        await().atMost(Duration.ofSeconds(5))
                .until(() -> fresh.load("c1").join().orElseThrow().get(COINS) == 7L);
        assertThat(engine.loadSnapshot(player).get().orElseThrow().get(COINS)).isEqualTo(7L);

        // subscribing through a closed scope never registers anything
        clans.onChange(COINS, (doc, before, after) -> storeEvents.add(-1L));
        assertThat(stores.watchedStores()).doesNotContain(new StoreRef("lobby", "clans"));
    }

    @Test
    void closeReleasesCachedStores() {
        PluginScope lobby = registry.get("lobby", k -> new PluginScope());
        PluginScope shop = registry.get("shop", k -> new PluginScope());
        StoreRef ref = new StoreRef("lobby", "clans");

        CachedStore a = lobby.store(stores.store(LOBBY, "clans"), stores).cached();
        CachedStore again = lobby.store(stores.store(LOBBY, "clans"), stores).cached();
        CachedStore b = shop.store(stores.store(LOBBY, "clans"), stores).cached();
        assertThat(stores.watchedStores()).contains(ref);

        registry.close("lobby");
        assertThat(stores.watchedStores()).contains(ref); // the shop still holds a share
        b.doc("c1").inc(COINS, 2);
        await().atMost(Duration.ofSeconds(5)).until(() -> b.get("c1").map(d -> d.get(COINS)).orElse(0L) == 2L);

        registry.close("shop");
        assertThat(stores.watchedStores()).doesNotContain(ref);
        assertThat(a).isNotNull();
        assertThat(again).isNotNull();
    }

    @Test
    void closeDropsListenersFromTheSharedEngineAndStores() throws Exception {
        // the guard silences a listener but would hide a missing unsubscribe; reachability does not lie
        PluginScope scope = registry.get("lobby", k -> new PluginScope());
        Store clans = scope.store(stores.store(LOBBY, "clans"), stores);
        WeakReference<Object> playerListener = registerPlayerListener(scope);
        WeakReference<Object> storeListener = registerStoreListener(clans);

        registry.close("lobby");
        runMainThread();

        await().atMost(Duration.ofSeconds(10)).until(() -> {
            System.gc();
            return playerListener.get() == null && storeListener.get() == null;
        });
    }

    @Test
    void retiredInstanceIsHandedOutUntilClosedButNeverRecreatedWhileDisabled() {
        PluginScope first = registry.get("lobby", true, k -> new PluginScope());
        registry.retire("lobby"); // PluginDisableEvent: the teardown follows on the next tick
        assertThat(first.isClosed()).isFalse();
        // inside the plugin's onDisable (enabled already false) and in the event window (still true)
        assertThat(registry.get("lobby", false, k -> new PluginScope())).isSameAs(first);
        assertThat(registry.get("lobby", true, k -> new PluginScope())).isSameAs(first);

        registry.close("lobby"); // the teardown tick
        assertThat(first.isClosed()).isTrue();
        assertThatThrownBy(() -> registry.get("lobby", false, k -> new PluginScope()))
                .isInstanceOf(IllegalStateException.class);
        // enabled again: a fresh instance
        PluginScope second = registry.get("lobby", true, k -> new PluginScope());
        assertThat(second).isNotSameAs(first);
        assertThat(second.isClosed()).isFalse();
    }

    @Test
    void concurrentRetireNeverHidesTheInstanceFromDisabledGet() throws Exception {
        // a get(enabled = false) racing retire must see the instance in one of the two maps, never in neither
        for (int round = 0; round < 2_000; round++) {
            PantheonRegistry<String, PluginScope> r = new PantheonRegistry<>();
            PluginScope scope = r.get("lobby", true, k -> new PluginScope());
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            Thread retirer = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                r.retire("lobby");
            });
            retirer.start();
            go.countDown();
            while (retirer.isAlive()) {
                assertThat(r.get("lobby", false, k -> new PluginScope())).isSameAs(scope);
            }
            retirer.join();
            assertThat(r.get("lobby", false, k -> new PluginScope())).isSameAs(scope);
        }
    }

    @Test
    void closeAllClosesRetiredInstances() {
        PluginScope lobby = registry.get("lobby", true, k -> new PluginScope());
        registry.retire("lobby");
        registry.closeAll();
        assertThat(lobby.isClosed()).isTrue();
    }

    private WeakReference<Object> registerPlayerListener(PluginScope scope) {
        PlayerChangeListener<Long> listener = new PlayerChangeListener<>() { // a fresh object, no cached lambda
            @Override
            public void changed(UUID player, Long before, Long after) {}
        };
        scope.onPlayerChange(engine, COINS, listener);
        return new WeakReference<>(listener);
    }

    private WeakReference<Object> registerStoreListener(Store store) {
        FieldListener<Long> listener = new FieldListener<>() {
            @Override
            public void changed(de.kalypzo.pantheon.data.Doc doc, Long before, Long after) {}
        };
        store.onChange(COINS, listener);
        return new WeakReference<>(listener);
    }

    // ---------- helpers ----------

    private void awaitWritesDone(UUID player) {
        await().atMost(Duration.ofSeconds(5)).until(() ->
                writer.idle(new de.kalypzo.pantheon.core.data.wire.StoreDoc(new StoreRef("lobby", "clans"), "c1"))
                        && writer.idle(new de.kalypzo.pantheon.core.data.wire.PlayerDoc(player)));
    }

    private void runMainThread() {
        Runnable r;
        while ((r = mainThread.poll()) != null) r.run();
    }

    private void openGate() {
        gateClosed = false;
        List<Runnable> open = new ArrayList<>(gated);
        gated.clear();
        open.forEach(Runnable::run);
    }

    /** Holds back {@code apply} while the gate is closed. */
    private DataBackend gate(DataBackend target) {
        return (DataBackend) Proxy.newProxyInstance(DataBackend.class.getClassLoader(),
                new Class<?>[] {DataBackend.class}, (proxy, method, args) -> {
                    try {
                        if (gateClosed && method.getName().equals("apply")) {
                            CompletableFuture<ApplyResult> later = new CompletableFuture<>();
                            gated.add(() -> {
                                @SuppressWarnings("unchecked")
                                CompletableFuture<ApplyResult> real =
                                        (CompletableFuture<ApplyResult>) invoke(method, target, args);
                                real.whenComplete((r, e) -> {
                                    if (e != null) later.completeExceptionally(e);
                                    else later.complete(r);
                                });
                            });
                            return later;
                        }
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
