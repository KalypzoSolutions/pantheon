package de.kalypzo.pantheon.core.store;

import static de.kalypzo.pantheon.data.Condition.gte;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.NodeState;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.data.wire.CreateResult;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.outbox.Outbox;
import de.kalypzo.pantheon.data.CachedStore;
import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.data.DuplicateValueException;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.data.SortOrder;
import de.kalypzo.pantheon.data.Store;
import de.kalypzo.pantheon.data.Subscription;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stores (P-21; data.md §4-§6, E6, E23, E24, R41, R43). */
class StoreTest {

    private static final DataNamespace LOBBY = DataNamespace.of("lobby");
    private static final DataNamespace OTHER = DataNamespace.of("other");
    private static final de.kalypzo.pantheon.data.Field<Long> COINS = LOBBY.ofLong("coins", 0L);
    private static final de.kalypzo.pantheon.data.Field<UUID> OWNER = LOBBY.ofUuid("owner");
    private static final de.kalypzo.pantheon.data.Field<Long> FOREIGN = OTHER.ofLong("coins", 0L);

    private static final class TestClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** One node: backend (optionally wrapped), writer, registry. */
    private final class Node implements AutoCloseable {
        final InMemoryNetwork.Node raw;
        final Backend backend;
        final Outbox outbox;
        final DocWriter writer;
        final StoreRegistry registry;
        final List<LogRecord> logs = new CopyOnWriteArrayList<>();

        Node(String id, java.util.function.UnaryOperator<Backend> wrap) {
            raw = network.node(id);
            backend = wrap.apply(raw);
            Logger log = Logger.getLogger("StoreTest." + id + "." + UUID.randomUUID());
            log.setUseParentHandlers(false);
            log.addHandler(new Handler() {
                @Override
                public void publish(LogRecord r) {
                    logs.add(r);
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            });
            outbox = Outbox.open(dir.resolve(id + "-outbox.db"), clock, log);
            writer = new DocWriter(backend, outbox, clock, Runnable::run, log);
            registry = new StoreRegistry(backend, writer, Runnable::run, log);
            backend.start(new NodeState() {
                @Override
                public Collection<UUID> heldPlayers() {
                    return List.of();
                }

                @Override
                public Collection<StoreRef> watchedStores() {
                    return registry.watchedStores();
                }
            });
        }

        Store store(String name) {
            return registry.store(LOBBY, name);
        }

        @Override
        public void close() {
            registry.close();
            outbox.close();
        }
    }

    @TempDir
    Path dir;

    private final TestClock clock = new TestClock();
    private InMemoryNetwork network;
    private Node a;
    private Node b;

    @BeforeEach
    void setUp() {
        network = new InMemoryNetwork(clock);
        a = new Node("a", x -> x);
        b = new Node("b", x -> x);
    }

    @AfterEach
    void tearDown() {
        a.close();
        b.close();
        network.close();
    }

    @Test
    void foreignNamespaceFieldRejected() {
        Store clans = a.store("clans");
        assertThatThrownBy(() -> clans.doc("x").set(FOREIGN, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other.coins")
                .hasMessageContaining("lobby:clans");
        assertThatThrownBy(() -> clans.create("y", d -> d.set(FOREIGN, 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other.coins");
        assertThatThrownBy(() -> clans.query().where(FOREIGN, gte(1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clans.index(FOREIGN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clans.onChange(FOREIGN, (d, x, y) -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createIsExclusiveAndReturnsFalseOnTakenId() {
        Store clans = a.store("clans");
        assertThat(clans.create("c1", d -> d.set(COINS, 5L)).join()).isTrue();
        assertThat(clans.create("c1", d -> d.set(COINS, 99L)).join()).isFalse();
        Doc loaded = clans.load("c1").join().orElseThrow();
        assertThat(loaded.get(COINS)).isEqualTo(5L);
        assertThat(clans.load("nope").join()).isEmpty();
    }

    @Test
    void createWithUniqueViolationFails() {
        Store clans = a.store("clans");
        clans.unique(OWNER).join();
        UUID owner = UUID.randomUUID();
        assertThat(clans.create("c1", d -> d.set(OWNER, owner)).join()).isTrue();
        assertThatThrownBy(() -> clans.create("c2", d -> d.set(OWNER, owner)).join())
                .hasCauseInstanceOf(DuplicateValueException.class);
        // R43: documents without an explicit value never collide
        assertThat(clans.create("c3", d -> {}).join()).isTrue();
        assertThat(clans.create("c4", d -> {}).join()).isTrue();
    }

    @Test
    void loadOrCreateHandlesRace() {
        AtomicBoolean fired = new AtomicBoolean();
        Node racer = new Node("racer", real -> spy(real, Map.of("load", () -> {
            if (fired.compareAndSet(false, true)) {
                // someone else creates the document between our load and our create
                b.backend.create("race-key", new StoreDoc(new StoreRef("lobby", "clans"), "r1"),
                        Map.of("coins", 7L)).join();
                return List.of(); // our load saw nothing
            }
            return null;
        })));
        try {
            Doc doc = racer.store("clans").loadOrCreate("r1").join();
            assertThat(doc.get(COINS)).isEqualTo(7L);
            // and the plain path creates
            Doc fresh = racer.store("clans").loadOrCreate("r2").join();
            assertThat(fresh.get(COINS)).isEqualTo(0L);
            assertThat(a.store("clans").load("r2").join()).isPresent();
        } finally {
            racer.close();
        }
    }

    @Test
    void remoteDocIncWithoutLoad() {
        a.store("clans").create("c1", d -> d.set(COINS, 10L)).join();
        b.store("clans").doc("c1").inc(COINS, 5);
        await().untilAsserted(
                () -> assertThat(a.store("clans").load("c1").join().orElseThrow().get(COINS)).isEqualTo(15L));
    }

    @Test
    void snapshotDocSeesOwnOpsButNotOthers() {
        Store clans = a.store("clans");
        clans.create("c1", d -> d.set(COINS, 1L)).join();
        Doc doc = clans.load("c1").join().orElseThrow();
        doc.inc(COINS, 2);
        assertThat(doc.get(COINS)).isEqualTo(3L);

        b.store("clans").doc("c1").inc(COINS, 10);
        await().untilAsserted(
                () -> assertThat(b.store("clans").load("c1").join().orElseThrow().get(COINS)).isEqualTo(13L));
        assertThat(doc.get(COINS)).isEqualTo(3L);
        assertThat(doc.has(COINS)).isTrue();
        assertThat(doc.find(OWNER)).isEmpty();
    }

    @Test
    void cachedStoreLoadsAllPagesAndStaysLive() {
        List<CompletableFuture<CreateResult>> creates = new ArrayList<>();
        StoreRef ref = new StoreRef("lobby", "warps");
        for (int i = 0; i < 2500; i++) {
            creates.add(b.backend.create("k" + i, new StoreDoc(ref, "d" + i), Map.of("coins", 1L)));
        }
        creates.forEach(CompletableFuture::join);

        CachedStore warps = a.registry.store(LOBBY, "warps").cached();
        assertThat(warps.all()).hasSize(2500);
        assertThat(warps.get("d2499")).isPresent();

        b.store("warps").doc("d17").inc(COINS, 5);
        await().untilAsserted(() -> assertThat(warps.get("d17").orElseThrow().get(COINS)).isEqualTo(6L));
        b.store("warps").create("fresh", d -> d.set(COINS, 9L)).join();
        await().untilAsserted(() -> assertThat(warps.get("fresh")).isPresent());
        assertThat(warps.all()).hasSize(2501);

        // own writes are visible at once
        warps.get("d1").orElseThrow().inc(COINS, 100);
        assertThat(warps.get("d1").orElseThrow().get(COINS)).isEqualTo(101L);
    }

    @Test
    void cachedStoreSeesDeletesAndExpiry() {
        Store bw = b.store("warps");
        bw.create("d1", d -> d.set(COINS, 1L)).join();
        bw.create("d2", d -> d.set(COINS, 2L)).join();
        bw.create("d3", d -> d.set(COINS, 3L)).join();
        CachedStore warps = a.store("warps").cached();
        assertThat(warps.all()).hasSize(3);

        assertThat(bw.delete("d1").join()).isTrue();
        await().untilAsserted(() -> assertThat(warps.get("d1")).isEmpty());

        bw.expire("d2", clock.instant().plusSeconds(60));
        clock.advance(Duration.ofMinutes(2));
        bw.load("anything").join(); // Agora/InMemoryNetwork purge before a call
        await().untilAsserted(() -> assertThat(warps.get("d2")).isEmpty());
        assertThat(warps.all()).extracting(Doc::id).containsExactly("d3");
    }

    @Test
    void cachedStoreStartsEmptyOnOutageAndReloads() {
        b.store("warps").create("d1", d -> d.set(COINS, 1L)).join();
        network.outage();
        CachedStore warps = a.store("warps").cached();
        assertThat(warps.all()).isEmpty();
        assertThat(a.logs).anyMatch(r -> r.getLevel() == Level.SEVERE);
        network.restore();
        a.registry.reloadCached();
        assertThat(warps.get("d1")).isPresent();
        // and it is live again (the node re-registered the watch on restore)
        b.store("warps").create("d2", d -> {}).join();
        await().untilAsserted(() -> assertThat(warps.get("d2")).isPresent());
    }

    @Test
    void reloadDropsDocumentsDeletedWhileAway() {
        b.store("warps").create("d1", d -> {}).join();
        b.store("warps").create("d2", d -> {}).join();
        CachedStore warps = a.store("warps").cached();
        assertThat(warps.all()).hasSize(2);
        network.outage();
        network.restore();
        b.store("warps").delete("d2").join();
        a.registry.reloadCached();
        assertThat(warps.all()).extracting(Doc::id).contains("d1");
        await().untilAsserted(() -> assertThat(warps.get("d2")).isEmpty());
    }

    @Test
    void ignoresChangesOfStoresNotWatched() {
        CachedStore warps = a.store("warps").cached();
        b.store("clans").create("x", d -> {}).join();
        b.store("warps").create("w", d -> {}).join();
        await().untilAsserted(() -> assertThat(warps.get("w")).isPresent());
        assertThat(warps.get("x")).isEmpty();
        assertThat(a.registry.watchedStores()).containsExactly(new StoreRef("lobby", "warps"));
    }

    @Test
    void onChangeFiresForOtherNodes() {
        List<String> seen = new CopyOnWriteArrayList<>();
        Subscription sub = a.store("clans").onChange(COINS,
                (doc, before, after) -> seen.add(doc.id() + ":" + before + "->" + after + "@" + doc.get(COINS)));
        b.store("clans").create("c1", d -> d.set(COINS, 1L)).join();
        b.store("clans").doc("c1").inc(COINS, 4);
        await().untilAsserted(() -> assertThat(seen).containsExactly("c1:0->1@1", "c1:1->5@5"));
        sub.unsubscribe();
    }

    @Test
    void unwatchWhenLastSubscriberGone() {
        List<String> calls = new CopyOnWriteArrayList<>();
        Node n = new Node("n", real -> spy(real, Map.of("watchStore", () -> {
            calls.add("watch");
            return null;
        }, "unwatchStore", () -> {
            calls.add("unwatch");
            return null;
        })));
        try {
            List<Long> seen = new CopyOnWriteArrayList<>();
            Subscription s1 = n.store("clans").onChange(COINS, (d, x, after) -> seen.add(after));
            Subscription s2 = n.store("clans").onChange(COINS, (d, x, after) -> seen.add(after));
            assertThat(calls).containsExactly("watch");
            assertThat(n.registry.watchedStores()).containsExactly(new StoreRef("lobby", "clans"));
            s1.unsubscribe();
            s1.unsubscribe(); // idempotent
            assertThat(calls).containsExactly("watch");
            s2.unsubscribe();
            assertThat(calls).containsExactly("watch", "unwatch");
            assertThat(n.registry.watchedStores()).isEmpty();
            b.store("clans").create("c", d -> d.set(COINS, 1L)).join();
            b.store("clans").load("c").join();
            assertThat(seen).isEmpty();
        } finally {
            n.close();
        }
    }

    @Test
    void queryLimitAbove1000Rejected() {
        Store clans = a.store("clans");
        assertThatThrownBy(() -> clans.query().limit(1001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clans.query().limit(0)).isInstanceOf(IllegalArgumentException.class);

        for (int i = 1; i <= 5; i++) {
            long coins = i;
            clans.create("c" + i, d -> d.set(COINS, coins)).join();
        }
        List<Doc> top = clans.query().where(COINS, gte(2L)).sortBy(COINS, SortOrder.DESC).limit(3).fetch().join();
        assertThat(top).extracting(Doc::id).containsExactly("c5", "c4", "c3");
        assertThat(clans.query().where(COINS, gte(2L)).count().join()).isEqualTo(4L);
        assertThat(clans.query().sortBy(COINS, SortOrder.ASC).first().join().orElseThrow().id()).isEqualTo("c1");
        assertThat(clans.query().limit(1000).fetch().join()).hasSize(5);
    }

    @Test
    void storeTransfer() {
        Store clans = a.store("clans");
        clans.create("x", d -> d.set(COINS, 100L)).join();
        clans.create("y", d -> d.set(COINS, 5L)).join();
        assertThat(clans.transfer(COINS, "x", "y", 30).join()).isTrue();
        assertThat(clans.load("x").join().orElseThrow().get(COINS)).isEqualTo(70L);
        assertThat(clans.load("y").join().orElseThrow().get(COINS)).isEqualTo(35L);
        assertThat(clans.transfer(COINS, "x", "y", 500).join()).isFalse();
        assertThat(clans.load("x").join().orElseThrow().get(COINS)).isEqualTo(70L);
    }

    @Test
    void withdrawAndAtomicOnStoreDocs() {
        Store clans = a.store("clans");
        clans.create("x", d -> d.set(COINS, 100L)).join();
        assertThat(clans.doc("x").withdraw(COINS, 120).join()).isFalse();
        assertThat(clans.doc("x").withdraw(COINS, 40).join()).isTrue();
        assertThat(clans.doc("x").atomic().require(COINS, gte(60L)).inc(COINS, -10).commit().join()).isTrue();
        assertThat(clans.load("x").join().orElseThrow().get(COINS)).isEqualTo(50L);
    }

    @Test
    void expireFailuresAreOnlyLogged() {
        network.outage();
        a.store("clans").expire("x", clock.instant().plusSeconds(5));
        await().untilAsserted(() -> assertThat(a.logs).anyMatch(r -> r.getMessage().contains("expire")));
        assertThat(a.outbox.pendingByDoc()).isEmpty();
    }

    // ---------- fix round 1 (R46, R47, retry) ----------

    private static Backend gated(Backend real, Map<String, CompletableFuture<Void>> gates, List<String> calls) {
        return (Backend) Proxy.newProxyInstance(Backend.class.getClassLoader(), new Class<?>[] {Backend.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    boolean tracked = Set.of("apply", "create", "delete", "load", "expire").contains(name);
                    java.util.concurrent.Callable<Object> real0 = () -> {
                        if (tracked) calls.add(name);
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw (Exception) e.getCause();
                        }
                    };
                    CompletableFuture<Void> gate = gates.get(name);
                    if (gate == null) return real0.call();
                    return gate.thenCompose(v -> {
                        try {
                            @SuppressWarnings("unchecked")
                            CompletableFuture<Object> f = (CompletableFuture<Object>) real0.call();
                            return f;
                        } catch (Exception e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    });
                });
    }

    @Test
    void deleteRunsAfterPendingOps() throws Exception {
        Map<String, CompletableFuture<Void>> gates = new HashMap<>();
        List<String> calls = new CopyOnWriteArrayList<>();
        Node n = new Node("g1", real -> gated(real, gates, calls));
        try {
            n.store("clans").create("x", d -> d.set(COINS, 1L)).join();
            calls.clear();
            gates.put("apply", new CompletableFuture<>());
            n.store("clans").doc("x").inc(COINS, 5);
            CompletableFuture<Boolean> deleted = n.store("clans").delete("x");
            Thread.sleep(150);
            assertThat(calls).doesNotContain("delete");
            gates.get("apply").complete(null);
            assertThat(deleted.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(calls).containsExactly("apply", "delete");
            assertThat(a.store("clans").load("x").join()).isEmpty();
        } finally {
            n.close();
        }
    }

    @Test
    void opsAfterCreateWaitForCreate() throws Exception {
        Map<String, CompletableFuture<Void>> gates = new HashMap<>();
        List<String> calls = new CopyOnWriteArrayList<>();
        gates.put("create", new CompletableFuture<>());
        Node n = new Node("g2", real -> gated(real, gates, calls));
        try {
            CompletableFuture<Boolean> created = n.store("clans").create("y", d -> d.set(COINS, 1L));
            n.store("clans").doc("y").inc(COINS, 5);
            Thread.sleep(150);
            assertThat(calls).doesNotContain("apply");
            gates.get("create").complete(null);
            assertThat(created.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            await().untilAsserted(() -> assertThat(a.store("clans").load("y").join().orElseThrow().get(COINS))
                    .isEqualTo(6L));
            assertThat(calls).containsExactly("create", "apply");
        } finally {
            n.close();
        }
    }

    @Test
    void loadSeesOwnPendingOps() throws Exception {
        Map<String, CompletableFuture<Void>> gates = new HashMap<>();
        List<String> calls = new CopyOnWriteArrayList<>();
        Node n = new Node("g3", real -> gated(real, gates, calls));
        try {
            n.store("clans").create("z", d -> d.set(COINS, 1L)).join();
            gates.put("apply", new CompletableFuture<>());
            n.store("clans").doc("z").inc(COINS, 5);
            CompletableFuture<java.util.Optional<Doc>> loaded = n.store("clans").load("z");
            Thread.sleep(100);
            assertThat(loaded).isNotDone();
            gates.get("apply").complete(null);
            assertThat(loaded.get(5, java.util.concurrent.TimeUnit.SECONDS).orElseThrow().get(COINS)).isEqualTo(6L);
        } finally {
            n.close();
        }
    }

    @Test
    void onChangeBeforeIsBestEffortAndStaleEventsStayIgnored() {
        int old = StoreRegistry.knownLimit;
        StoreRegistry.knownLimit = 2;
        Backend[] holder = new Backend[1];
        Node n = new Node("lru", real -> {
            // every change event arrives twice
            holder[0] = (Backend) Proxy.newProxyInstance(Backend.class.getClassLoader(), new Class<?>[] {Backend.class},
                    (proxy, method, args) -> {
                        try {
                            if (method.getName().equals("onDocChanged")) {
                                @SuppressWarnings("unchecked")
                                java.util.function.Consumer<de.kalypzo.pantheon.core.data.wire.DocChanged> l =
                                        (java.util.function.Consumer<de.kalypzo.pantheon.core.data.wire.DocChanged>) args[0];
                                return real.onDocChanged(c -> {
                                    l.accept(c);
                                    l.accept(c);
                                });
                            }
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            return holder[0];
        });
        try {
            List<String> seen = new CopyOnWriteArrayList<>();
            n.store("clans").onChange(COINS, (d, before, after) -> seen.add(d.id() + ":" + before + "->" + after));
            Store bs = b.store("clans");
            bs.create("d1", d -> d.set(COINS, 1L)).join();
            bs.create("d2", d -> d.set(COINS, 2L)).join();
            bs.create("d3", d -> d.set(COINS, 3L)).join(); // evicts d1 (limit 2)
            bs.doc("d1").inc(COINS, 4);
            await().untilAsserted(() -> assertThat(seen).contains("d1:0->5"));
            assertThat(seen).containsExactly("d1:0->1", "d2:0->2", "d3:0->3", "d1:0->5"); // duplicates did not refire
        } finally {
            StoreRegistry.knownLimit = old;
            n.close();
        }
    }

    @Test
    void failedInitialLoadIsRetriedWhileConnected() {
        b.store("warps").create("d1", d -> d.set(COINS, 1L)).join();
        network.outage();
        CachedStore warps = a.store("warps").cached();
        assertThat(warps.all()).isEmpty();
        network.restore();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(warps.get("d1")).isPresent());
    }

    @Test
    void delayedInitialLoadFiresNoListenersLikeTheInitialLoad() {
        // deferred P-21 / final review M-8: the retry (or a reload) completing a failed initial load is the initial load
        b.store("warps").create("d1", d -> d.set(COINS, 1L)).join();
        b.store("warps").create("d2", d -> d.set(COINS, 2L)).join();
        List<String> seen = new CopyOnWriteArrayList<>();
        a.store("warps").onChange(COINS, (doc, before, after) -> seen.add(doc.id() + ":" + before + "->" + after));
        network.outage();
        CachedStore warps = a.store("warps").cached();
        assertThat(warps.all()).isEmpty();
        network.restore();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(warps.all()).hasSize(2));
        assertThat(seen).isEmpty();
        // later changes fire as usual
        b.store("warps").doc("d1").inc(COINS, 1);
        await().untilAsserted(() -> assertThat(seen).containsExactly("d1:1->2"));
    }

    @Test
    void releaseStopsWatchingViaRegistry() {
        CachedStore warps = a.store("warps").cached();
        assertThat(a.registry.watchedStores()).hasSize(1);
        a.registry.release(warps);
        assertThat(a.registry.watchedStores()).isEmpty();
    }

    // ---------- final review I-1: own writes/creates/deletes of documents not (yet) in the cache ----------

    /** Holds back the DocChanged events of {@code real} while {@code hold} is set; {@code release} delivers them. */
    private static final class HeldEvents {
        final AtomicBoolean hold = new AtomicBoolean();
        final List<Runnable> held = new CopyOnWriteArrayList<>();

        Backend wrap(Backend real) {
            return (Backend) Proxy.newProxyInstance(Backend.class.getClassLoader(), new Class<?>[] {Backend.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("onDocChanged")) {
                            @SuppressWarnings("unchecked")
                            var listener = (java.util.function.Consumer<de.kalypzo.pantheon.core.data.wire.DocChanged>)
                                    args[0];
                            return real.onDocChanged(c -> {
                                if (hold.get()) held.add(() -> listener.accept(c));
                                else listener.accept(c);
                            });
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        void release() {
            hold.set(false);
            List<Runnable> now = List.copyOf(held);
            held.clear();
            now.forEach(Runnable::run);
        }
    }

    @Test
    void ownWriteToNewDocIsVisibleInCacheDuringOutage() {
        CachedStore warps = a.store("warps").cached();
        List<String> seen = new CopyOnWriteArrayList<>();
        a.store("warps").onChange(COINS, (doc, before, after) -> seen.add(doc.id() + ":" + before + "->" + after));
        network.outage();

        a.store("warps").doc("newwarp").set(COINS, 5L);

        assertThat(warps.get("newwarp")).isPresent().get().satisfies(d -> assertThat(d.get(COINS)).isEqualTo(5L));
        assertThat(warps.all()).extracting(Doc::id).containsExactly("newwarp");
        assertThat(seen).containsExactly("newwarp:0->5");
        warps.get("newwarp").orElseThrow().inc(COINS, 1);
        assertThat(warps.get("newwarp").orElseThrow().get(COINS)).isEqualTo(6L);

        // a reload while the write still waits in the outbox keeps the document (its pending ops recreate it)
        network.restore();
        a.registry.reloadCached();
        assertThat(warps.get("newwarp")).isPresent();
    }

    @Test
    void ownCreateAndDeleteAreVisibleBeforeTheirEcho() {
        HeldEvents events = new HeldEvents();
        Node n = new Node("held", events::wrap);
        try {
            CachedStore warps = n.store("warps").cached();
            events.hold.set(true);

            assertThat(n.store("warps").create("w1", d -> d.set(COINS, 9L)).join()).isTrue();
            assertThat(warps.get("w1")).isPresent().get().satisfies(d -> assertThat(d.get(COINS)).isEqualTo(9L));

            n.store("warps").doc("w2").set(COINS, 3L);
            assertThat(warps.get("w2")).isPresent();

            assertThat(n.store("warps").delete("w1").join()).isTrue();
            assertThat(warps.get("w1")).isEmpty();

            // the held echoes (create w1, write w2, delete w1) arrive late: nothing comes back, nothing is lost
            events.release();
            assertThat(warps.get("w1")).isEmpty();
            assertThat(warps.all()).extracting(Doc::id).containsExactly("w2");
            assertThat(warps.get("w2").orElseThrow().get(COINS)).isEqualTo(3L);

            // a later remote create of the same id is seen again
            b.store("warps").create("w1", d -> d.set(COINS, 1L)).join();
            await().untilAsserted(() -> assertThat(warps.get("w1")).isPresent());
        } finally {
            n.close();
        }
    }

    @Test
    void deleteEchoBeforeResultDoesNotDropALaterCreate() {
        // echoes are delivered synchronously here: the delete echo arrives before the delete result
        CachedStore warps = a.store("warps").cached();
        a.store("warps").create("w1", d -> d.set(COINS, 1L)).join();
        assertThat(a.store("warps").delete("w1").join()).isTrue();
        assertThat(warps.get("w1")).isEmpty();
        b.store("warps").create("w1", d -> d.set(COINS, 2L)).join();
        await().untilAsserted(() -> assertThat(warps.get("w1")).isPresent());
    }

    /**
     * Wraps a backend: {@code map} holds a hook per method name. A supplier result of
     * {@code null} means: call the real method; for {@code watchStore}/{@code unwatchStore} the supplier records and
     * the real method runs too.
     */
    private static Backend spy(Backend real, Map<String, java.util.function.Supplier<Object>> map) {
        return (Backend) Proxy.newProxyInstance(Backend.class.getClassLoader(), new Class<?>[] {Backend.class},
                (proxy, method, args) -> {
                    var hook = map.get(method.getName());
                    if (hook != null) {
                        Object result = hook.get();
                        if (result != null) {
                            return method.getName().equals("load")
                                    ? CompletableFuture.completedFuture(result) : result;
                        }
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
