package de.kalypzo.pantheon.core.player;

import static de.kalypzo.pantheon.data.Condition.contains;
import static de.kalypzo.pantheon.data.Condition.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.NodeState;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
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
import de.kalypzo.pantheon.data.SortOrder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Player data (P-20; player.md §5-§8, data.md §3, §5, §6, E9, E10, E15). */
class PlayerDataEngineTest {

    private static final DataNamespace ECONOMY = DataNamespace.of("economy");
    private static final DataNamespace LOBBY = DataNamespace.of("lobby");
    private static final Field<Long> COINS = ECONOMY.ofLong("coins", 0L);
    private static final Field<Long> GEMS = ECONOMY.ofLong("gems", 0L);
    private static final Field<Set<String>> KITS = LOBBY.ofSet("kits", String.class);
    private static final Field<Long> MONEY = LOBBY.ofLong("money", 0L);
    private static final Field<Long> RENAMED = LOBBY.ofLong("coins", 0L).readFrom("money");

    private record Change<T>(UUID player, T before, T after, String thread) {}

    /** One node: backend (optionally wrapped), writer, engine. */
    private final class Node implements AutoCloseable {
        final Backend backend;
        final Outbox outbox;
        final DocWriter writer;
        final PlayerDataEngine engine;

        Node(String id, Executor listeners, Map<String, Supplier<CompletableFuture<?>>> gates) {
            InMemoryNetwork.Node raw = network.node(id);
            backend = gated(raw, gates);
            Logger log = Logger.getLogger("PlayerDataEngineTest." + id);
            outbox = Outbox.open(dir.resolve(id + "-outbox.db"), Clock.systemUTC(), log);
            writer = new DocWriter(backend, outbox, Clock.systemUTC(), Runnable::run, log);
            engine = new PlayerDataEngine(backend, writer, listeners, log);
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
        }

        Node(String id) {
            this(id, Runnable::run, Map.of());
        }

        Doc load(UUID player, String name) throws Exception {
            return engine.load(player, name, Duration.ofSeconds(5)).get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
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

    private Node node(String id, Executor listeners, Map<String, Supplier<CompletableFuture<?>>> gates) {
        Node n = new Node(id, listeners, gates);
        closeables.add(n);
        return n;
    }

    /**
     * Delegates to {@code real}; a call named in {@code gates} waits for the gate's future first (a gate returning
     * null passes through). Under {@code "hold:" + name} the real call runs at once and only its result waits.
     */
    private static Backend gated(Backend real, Map<String, Supplier<CompletableFuture<?>>> gates) {
        return (Backend) Proxy.newProxyInstance(Backend.class.getClassLoader(), new Class<?>[] {Backend.class},
                (proxy, method, args) -> {
                    Supplier<CompletableFuture<?>> hold = gates.get("hold:" + method.getName());
                    CompletableFuture<?> h = hold == null ? null : hold.get();
                    if (h != null) {
                        CompletableFuture<?> result = (CompletableFuture<?>) invoke(real, method, args);
                        return result.thenCompose(r -> h.thenApply(v -> r));
                    }
                    Supplier<CompletableFuture<?>> gate = gates.get(method.getName());
                    if (gate == null) return invoke(real, method, args);
                    CompletableFuture<?> g = gate.get();
                    if (g == null) return invoke(real, method, args);
                    return g.thenCompose(v -> {
                        try {
                            return (CompletableFuture<?>) invoke(real, method, args);
                        } catch (Throwable e) {
                            return CompletableFuture.failedFuture(e);
                        }
                    });
                });
    }

    private static Object invoke(Object real, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        return f.get(5, TimeUnit.SECONDS);
    }

    /** Writes through a node of its own and waits until the database has it. */
    private void seed(UUID player, Field<Long> field, long value) throws Exception {
        Node seeder = node("seed-" + UUID.randomUUID());
        seeder.engine.remote(player).set(PlayerFields.NAME, "p" + player.toString().substring(0, 4));
        seeder.engine.remote(player).set(field, value);
        await().atMost(Duration.ofSeconds(5)).until(() -> seeder.writer.idle(new PlayerDoc(player)));
    }

    private long stored(UUID player, String namespace, String name) throws Exception {
        for (Row r : get(network.node("reader-" + UUID.randomUUID()).load(new PlayerDoc(player)))) {
            if (((PlayerRow) r.ref()).namespace().equals(namespace)) {
                Object v = r.data().get(name);
                return v == null ? 0 : ((Number) v).longValue();
            }
        }
        return 0;
    }

    // ---------- tests ----------

    @Test
    void loadReturnsAllNamespaces() throws Exception {
        UUID p = UUID.randomUUID();
        seed(p, COINS, 100);
        Node seeder = node("seed2");
        seeder.engine.remote(p).add(KITS, "warrior");
        await().until(() -> seeder.writer.idle(new PlayerDoc(p)));

        Doc doc = node("a").load(p, "Steve");

        assertThat(doc.get(COINS)).isEqualTo(100L);
        assertThat(doc.get(KITS)).containsExactly("warrior");
        assertThat(doc.get(PlayerFields.NAME)).isEqualTo("Steve");
        assertThat(doc.id()).isEqualTo(p.toString());
    }

    @Test
    void loadTimesOut() {
        CompletableFuture<Void> never = new CompletableFuture<>();
        List<String> calls = new CopyOnWriteArrayList<>();
        Node a = node("a", Runnable::run, Map.of("loadPlayer", () -> never, "releasePlayer", () -> {
            calls.add("releasePlayer");
            return null;
        }));
        UUID p = UUID.randomUUID();

        CompletableFuture<Doc> load = a.engine.load(p, "Steve", Duration.ofMillis(200));

        assertThatThrownBy(() -> load.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DataUnavailableException.class);
        assertThat(a.engine.profile(p)).isEmpty();
        assertThat(a.engine.heldPlayers()).doesNotContain(p);
        assertThat(calls).containsExactly("releasePlayer");
    }

    @Test
    void loadStoresName() throws Exception {
        UUID p = UUID.randomUUID();
        Node a = node("a");

        Doc doc = a.load(p, "Steve");

        assertThat(doc.get(PlayerFields.NAME)).isEqualTo("Steve");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Optional<Doc> snap = get(node("b").engine.loadSnapshot(p));
            assertThat(snap).isPresent();
            assertThat(snap.get().get(PlayerFields.NAME)).isEqualTo("Steve");
        });
        assertThat(a.engine.profile(p)).containsSame(doc);
        assertThat(a.engine.heldPlayers()).containsExactly(p);
    }

    @Test
    void writesVisibleImmediately() throws Exception {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        Map<String, Supplier<CompletableFuture<?>>> gates = new ConcurrentHashMap<>();
        Node a = node("a", Runnable::run, gates);
        UUID p = UUID.randomUUID();
        Doc doc = a.load(p, "Steve");
        gates.put("apply", () -> gate);

        doc.inc(COINS, 5);
        doc.add(KITS, "warrior");

        assertThat(doc.get(COINS)).isEqualTo(5L);
        assertThat(doc.get(KITS)).containsExactly("warrior");
        assertThat(stored(p, "economy", "coins")).isZero();
        gate.complete(null);
        await().atMost(Duration.ofSeconds(5)).until(() -> stored(p, "economy", "coins") == 5);
    }

    @Test
    void withdrawVisibleOnlyAfterConfirmation() throws Exception {
        UUID p = UUID.randomUUID();
        seed(p, COINS, 100);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        Map<String, Supplier<CompletableFuture<?>>> gates = new ConcurrentHashMap<>();
        Node a = node("a", Runnable::run, gates);
        Doc doc = a.load(p, "Steve");
        await().until(() -> a.writer.idle(new PlayerDoc(p)));
        gates.put("apply", () -> gate);

        CompletableFuture<Boolean> withdraw = doc.withdraw(COINS, 30);

        assertThat(withdraw).isNotDone();
        assertThat(doc.get(COINS)).isEqualTo(100L);
        gate.complete(null);
        assertThat(get(withdraw)).isTrue();
        assertThat(doc.get(COINS)).isEqualTo(70L);
    }

    @Test
    void atomicBuyExample() throws Exception {
        UUID p = UUID.randomUUID();
        seed(p, COINS, 100);
        Doc data = node("a").load(p, "Steve");
        long price = 30;

        boolean first = get(data.atomic()
                .withdraw(COINS, price)
                .require(KITS, not(contains("warrior")))
                .add(KITS, "warrior")
                .commit());
        boolean second = get(data.atomic()
                .withdraw(COINS, price)
                .require(KITS, not(contains("warrior")))
                .add(KITS, "warrior")
                .commit());

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(data.get(COINS)).isEqualTo(70L);
        assertThat(data.get(KITS)).containsExactly("warrior");
        assertThat(stored(p, "economy", "coins")).isEqualTo(70);
    }

    @Test
    void changeFromOtherServerFiresOnChange() throws Exception {
        UUID p = UUID.randomUUID();
        seed(p, COINS, 100);
        Node a = node("a");
        Node b = node("b");
        List<Change<Long>> changes = new CopyOnWriteArrayList<>();
        b.engine.onChange(COINS, (player, before, after) ->
                changes.add(new Change<>(player, before, after, Thread.currentThread().getName())));
        Doc onA = a.load(p, "Steve");
        Doc onB = b.load(p, "Steve");

        assertThat(get(onA.withdraw(COINS, 30))).isTrue();

        await().atMost(Duration.ofSeconds(5)).until(() -> !changes.isEmpty());
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().player()).isEqualTo(p);
        assertThat(changes.getFirst().before()).isEqualTo(100L);
        assertThat(changes.getFirst().after()).isEqualTo(70L);
        assertThat(onB.get(COINS)).isEqualTo(70L);
    }

    @Test
    void onChangeNotFiredWhenValueSame() throws Exception {
        UUID p = UUID.randomUUID();
        seed(p, COINS, 100);
        ExecutorService main = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-main"));
        closeables.add(main::shutdownNow);
        Node a = node("a", main, Map.of());
        List<Change<Long>> changes = new CopyOnWriteArrayList<>();
        a.engine.onChange(COINS, (player, before, after) -> changes.add(new Change<>(player, before, after, "")));
        Doc doc = a.load(p, "Steve");

        doc.set(COINS, 100L);       // same value
        doc.max(COINS, 50L);        // smaller, no change
        doc.inc(GEMS, 3);           // other field, same namespace
        doc.inc(COINS, 0);          // no change
        await().until(() -> a.writer.idle(new PlayerDoc(p)));
        doc.inc(COINS, 1);          // marker: the only change
        await().until(() -> a.writer.idle(new PlayerDoc(p)));
        main.submit(() -> {}).get(5, TimeUnit.SECONDS); // serial executor: everything submitted before has run

        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().before()).isEqualTo(100L);
        assertThat(changes.getFirst().after()).isEqualTo(101L);
    }

    @Test
    void remoteIncReachesLoadedPlayerOnOtherNode() throws Exception {
        UUID p = UUID.randomUUID();
        Node a = node("a");
        Node b = node("b");
        List<Change<Long>> changes = new CopyOnWriteArrayList<>();
        b.engine.onChange(COINS, (player, before, after) -> changes.add(new Change<>(player, before, after, "")));
        Doc onB = b.load(p, "Steve");

        a.engine.remote(p).inc(COINS, 100);

        await().atMost(Duration.ofSeconds(5)).until(() -> onB.get(COINS) == 100L);
        await().atMost(Duration.ofSeconds(5)).until(() -> changes.size() == 1);
        assertThat(changes.getFirst().before()).isZero();
        assertThat(changes.getFirst().after()).isEqualTo(100L);
        assertThat(a.engine.heldPlayers()).doesNotContain(p);
    }

    @Test
    void rankTiesShareRank() throws Exception {
        UUID rich = UUID.randomUUID();
        UUID tieA = UUID.randomUUID();
        UUID tieB = UUID.randomUUID();
        UUID poor = UUID.randomUUID();
        seed(rich, COINS, 200);
        seed(tieA, COINS, 100);
        seed(tieB, COINS, 100);
        seed(poor, COINS, 50);
        Node a = node("a");
        a.load(tieA, "TieA"); // from the loaded profile

        assertThat(get(a.engine.rank(COINS, rich))).isEqualTo(1L);
        assertThat(get(a.engine.rank(COINS, tieA))).isEqualTo(2L);
        assertThat(get(a.engine.rank(COINS, tieB))).isEqualTo(2L);
        assertThat(get(a.engine.rank(COINS, poor))).isEqualTo(4L);
        List<Doc> top = get(a.engine.query().sortBy(COINS, SortOrder.DESC).limit(2).fetch());
        assertThat(top).extracting(d -> d.get(COINS)).containsExactly(200L, 100L);
        assertThat(top.getFirst().get(PlayerFields.NAME)).isNotEmpty();
    }

    @Test
    void rankOfUnknownPlayerUsesDefault() throws Exception {
        seed(UUID.randomUUID(), COINS, 200);
        seed(UUID.randomUUID(), COINS, 100);
        seed(UUID.randomUUID(), COINS, 0);
        Node a = node("a");

        // default 0: two players have more, the one with 0 shares the rank
        assertThat(get(a.engine.rank(COINS, UUID.randomUUID()))).isEqualTo(3L);
    }

    @Test
    void transferUpdatesLoadedProfiles() throws Exception {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        seed(from, COINS, 100);
        seed(to, COINS, 10);
        Node a = node("a");
        Node b = node("b");
        Doc sender = a.load(from, "From");
        Doc receiverOnB = b.load(to, "To");

        assertThat(get(a.engine.transfer(COINS, from, to, 30))).isTrue();
        assertThat(sender.get(COINS)).isEqualTo(70L);
        await().atMost(Duration.ofSeconds(5)).until(() -> receiverOnB.get(COINS) == 40L);

        assertThat(get(a.engine.transfer(COINS, from, to, 1000))).isFalse();
        assertThat(sender.get(COINS)).isEqualTo(70L);
    }

    @Test
    void readFromAliasReadsOldName() throws Exception {
        UUID p = UUID.randomUUID();
        seed(p, MONEY, 42);
        Node a = node("a");
        Doc doc = a.load(p, "Steve");

        assertThat(doc.get(RENAMED)).isEqualTo(42L);

        doc.set(RENAMED, 50L);
        await().until(() -> a.writer.idle(new PlayerDoc(p)));
        assertThat(doc.get(RENAMED)).isEqualTo(50L);
        assertThat(stored(p, "lobby", "coins")).isEqualTo(50);
        assertThat(stored(p, "lobby", "money")).isEqualTo(42);
    }

    @Test
    void releaseKeepsPendingWritesRunning() throws Exception {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        Map<String, Supplier<CompletableFuture<?>>> gates = new ConcurrentHashMap<>();
        Node a = node("a", Runnable::run, gates);
        UUID p = UUID.randomUUID();
        Doc doc = a.load(p, "Steve");
        await().until(() -> a.writer.idle(new PlayerDoc(p)));
        gates.put("apply", () -> gate);

        doc.inc(COINS, 25);
        a.engine.release(p);

        assertThat(a.engine.profile(p)).isEmpty();
        assertThat(a.engine.heldPlayers()).doesNotContain(p);
        gate.complete(null);
        await().atMost(Duration.ofSeconds(5)).until(() -> stored(p, "economy", "coins") == 25);
    }

    @Test
    void listenersRunOnGivenExecutor() throws Exception {
        ExecutorService main = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-main"));
        closeables.add(main::shutdownNow);
        Node a = node("a", main, Map.of());
        Node b = node("b");
        UUID p = UUID.randomUUID();
        List<Change<Long>> changes = new CopyOnWriteArrayList<>();
        a.engine.onChange(COINS, (player, before, after) ->
                changes.add(new Change<>(player, before, after, Thread.currentThread().getName())));
        Doc doc = a.load(p, "Steve");

        doc.inc(COINS, 5);                         // own write
        await().until(() -> changes.size() == 1);
        b.engine.remote(p).inc(COINS, 10);         // foreign change
        await().until(() -> changes.size() == 2);
        assertThat(get(doc.withdraw(COINS, 15))).isTrue(); // confirmed conditional
        await().until(() -> changes.size() == 3);

        assertThat(changes).extracting(Change::thread).containsOnly("test-main");
        assertThat(changes).extracting(Change::after).containsExactly(5L, 15L, 0L);
    }

    @Test
    void queryRejectsCollectionSortAndLargeLimit() {
        Node a = node("a");

        assertThatThrownBy(() -> a.engine.query().sortBy(KITS, SortOrder.DESC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.engine.query().limit(1001)).isInstanceOf(IllegalArgumentException.class);
        assertThat(a.engine.query().limit(1000)).isNotNull();
    }

    @Test
    void reloadAfterReleaseWaitsForStreamBarrier() throws Exception {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        List<String> calls = new CopyOnWriteArrayList<>();
        Map<String, Supplier<CompletableFuture<?>>> gates = new ConcurrentHashMap<>();
        gates.put("loadPlayer", () -> {
            calls.add("loadPlayer");
            return null;
        });
        Node a = node("a", Runnable::run, gates);
        UUID p = UUID.randomUUID();
        a.load(p, "Steve");
        assertThat(calls).hasSize(1);
        a.engine.release(p);
        gates.put("streamBarrier", () -> barrier);

        CompletableFuture<Doc> again = a.engine.load(p, "Steve", Duration.ofSeconds(5));

        assertThat(again).isNotDone();
        assertThat(calls).hasSize(1); // R45: no LoadPlayer before the release went through
        barrier.complete(null);
        assertThat(get(again).get(PlayerFields.NAME)).isEqualTo("Steve");
        assertThat(calls).hasSize(2);
    }

    @Test
    void lateLoadReplyAfterTimeoutReleasesAgain() throws Exception {
        CompletableFuture<Void> hold = new CompletableFuture<>();
        List<String> calls = new CopyOnWriteArrayList<>();
        Node a = node("a", Runnable::run, Map.of("hold:loadPlayer", () -> hold, "releasePlayer", () -> {
            calls.add("releasePlayer");
            return null;
        }));
        UUID p = UUID.randomUUID();

        CompletableFuture<Doc> load = a.engine.load(p, "Steve", Duration.ofMillis(200));
        assertThatThrownBy(() -> load.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(DataUnavailableException.class);
        assertThat(calls).hasSize(1);
        hold.complete(null); // the abandoned LoadPlayer answers late

        assertThat(calls).hasSize(2);
        assertThat(a.engine.heldPlayers()).isEmpty();
    }

    @Test
    void loadDuringReleaseWaitsForReleaseAndBarrier() throws Exception {
        java.util.concurrent.CountDownLatch inRelease = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<String> calls = new CopyOnWriteArrayList<>();
        Map<String, Supplier<CompletableFuture<?>>> gates = new ConcurrentHashMap<>();
        gates.put("loadPlayer", () -> {
            calls.add("loadPlayer");
            return null;
        });
        gates.put("streamBarrier", () -> {
            calls.add("streamBarrier");
            return null;
        });
        Node a = node("a", Runnable::run, gates);
        UUID p = UUID.randomUUID();
        a.load(p, "Steve");
        calls.clear();
        gates.put("releasePlayer", () -> {
            calls.add("releasePlayer");
            inRelease.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        Thread releasing = new Thread(() -> a.engine.release(p));
        releasing.start();
        assertThat(inRelease.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Doc> again = CompletableFuture
                .supplyAsync(() -> a.engine.load(p, "Steve", Duration.ofSeconds(5))).thenCompose(f -> f);

        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                .until(() -> !calls.contains("loadPlayer")); // no LoadPlayer while the release is not sent
        go.countDown();
        releasing.join(5000);

        assertThat(get(again).get(PlayerFields.NAME)).isEqualTo("Steve");
        assertThat(calls).containsExactly("releasePlayer", "streamBarrier", "loadPlayer");
    }

    @Test
    void changeArrivingWhileLoadingIsKept() throws Exception {
        UUID p = UUID.randomUUID();
        seed(p, COINS, 100);
        CompletableFuture<Void> hold = new CompletableFuture<>();
        Map<String, Supplier<CompletableFuture<?>>> gates = new ConcurrentHashMap<>();
        gates.put("hold:loadPlayer", () -> hold);
        Node a = node("a");
        Node b = node("b", Runnable::run, gates);
        List<DocChanged> seen = new CopyOnWriteArrayList<>();
        b.backend.onDocChanged(seen::add); // after the engine's own subscription

        // B reads now (no lobby row yet), the reply is held back
        CompletableFuture<Doc> load = b.engine.load(p, "Steve", Duration.ofSeconds(5));
        // A creates the lobby row meanwhile (player.md §8); B gets the change over the stream first
        a.engine.remote(p).add(KITS, "warrior");
        await().atMost(Duration.ofSeconds(5)).until(() -> seen.stream()
                .anyMatch(c -> ((PlayerRow) c.ref()).namespace().equals("lobby")));
        hold.complete(null);

        Doc doc = get(load);
        assertThat(doc.get(KITS)).containsExactly("warrior");
        assertThat(doc.get(COINS)).isEqualTo(100L);
    }

    @Test
    void snapshotTakesResultRowsAndKeepsLaterOwnOps() throws Exception {
        UUID p = UUID.randomUUID();
        seed(p, COINS, 100);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        Map<String, Supplier<CompletableFuture<?>>> gates = new ConcurrentHashMap<>();
        Node a = node("a", Runnable::run, gates);
        Doc snapshot = get(a.engine.loadSnapshot(p)).orElseThrow();
        gates.put("apply", () -> gate);

        CompletableFuture<Boolean> withdraw = snapshot.withdraw(COINS, 30);
        snapshot.inc(COINS, 5); // sent after the conditional request, queued behind it
        assertThat(snapshot.get(COINS)).isEqualTo(105L);
        gate.complete(null);

        assertThat(get(withdraw)).isTrue();
        assertThat(snapshot.get(COINS)).isEqualTo(75L); // rows (70) plus the later inc
        await().atMost(Duration.ofSeconds(5)).until(() -> stored(p, "economy", "coins") == 75);
    }
}
