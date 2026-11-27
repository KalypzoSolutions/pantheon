package de.kalypzo.pantheon.core.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.core.data.codec.FieldCodec;
import de.kalypzo.pantheon.core.data.codec.FieldCodecs;
import de.kalypzo.pantheon.core.data.codec.FieldSpecs;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.CreateResult;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.DocSnapshot;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.IndexDeclaration;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.Predicate;
import de.kalypzo.pantheon.core.data.wire.QuerySpec;
import de.kalypzo.pantheon.core.data.wire.QueryTarget;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.RowRef;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import de.kalypzo.pantheon.core.data.wire.SortSpec;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.data.wire.TransferResult;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.data.DataUnavailableException;
import de.kalypzo.pantheon.data.DuplicateValueException;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.data.PlayerFields;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The data contract (agora.md §2, §5; data.md §8): every {@link DataBackend} implementation must pass it, the
 * in-memory network as well as a real Agora. Works only through the ports; every test uses fresh players, stores and
 * documents, so it also runs against a shared database.
 */
public abstract class DataBackendContract {

    protected static final DataNamespace NS = DataNamespace.of("contract");
    protected static final DataNamespace KITS_NS = DataNamespace.of("contract_kits");
    protected static final Field<Long> COINS = NS.ofLong("coins", 100);
    protected static final Field<Integer> LEVEL = NS.ofInt("level", 1);
    protected static final Field<Long> SCORE = NS.ofLong("score", 0);
    protected static final Field<UUID> OWNER = NS.ofUuid("owner");
    protected static final Field<Instant> SEEN = NS.ofInstant("seen");
    protected static final Field<String> TAG = NS.ofString("tag", "");
    protected static final Field<Set<String>> KITS = KITS_NS.ofSet("kits", String.class);
    /** A numeric field without default, only possible on the wire. */
    protected static final FieldSpec BARE = new FieldSpec("contract", "bare", ScalarType.LONG, null);

    private static final Duration WAIT = Duration.ofSeconds(10);

    private ContractFixture fx;
    private Backend a;
    private Backend b;

    /** A new, empty-enough network; called once per test and closed after it. */
    protected abstract ContractFixture fixture();

    @BeforeEach
    void setUpContract() {
        fx = fixture();
        a = fx.node("lobby-01");
        b = fx.node("lobby-02");
    }

    @AfterEach
    void tearDownContract() {
        fx.close();
    }

    // ---------- holders ----------

    @Test
    void loadPlayerMakesNodeHolder() throws Exception {
        UUID p = UUID.randomUUID();
        get(b.apply(write(new PlayerDoc(p), inc(COINS, 5))));
        List<DocChanged> events = collect(a);

        List<Row> loaded = get(a.loadPlayer(p));
        long before = row(loaded, "contract").version();
        get(b.apply(write(new PlayerDoc(p), inc(COINS, 5))));

        PlayerRow ref = new PlayerRow(p, "contract");
        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.ref().equals(ref)));
        DocChanged e = events.stream().filter(x -> x.ref().equals(ref)).findFirst().orElseThrow();
        assertThat(e.version()).isGreaterThan(before);
        assertThat(e.deleted()).isFalse();
        assertThat(decode(COINS, e.data())).isEqualTo(110L);
    }

    @Test
    void releaseStopsChanges() throws Exception {
        UUID p = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        List<DocChanged> events = collect(a);
        get(a.loadPlayer(p));
        get(a.loadPlayer(marker));
        a.releasePlayer(p);
        // barrier: the release travels on A's stream; a watch behind it is active only after the release
        StoreRef barrier = newStore();
        a.watchStore(barrier);
        awaitWatched(barrier, events);

        PlayerRow markerRow = new PlayerRow(marker, "contract");
        get(b.apply(write(new PlayerDoc(p), inc(COINS, 1))));
        get(b.apply(write(new PlayerDoc(marker), inc(COINS, 1))));
        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.ref().equals(markerRow)));

        assertThat(events).noneMatch(e -> e.ref().equals(new PlayerRow(p, "contract")));
    }

    @Test
    void unknownPlayerLoadsNoRows() throws Exception {
        UUID p = UUID.randomUUID();
        assertThat(get(a.loadPlayer(p))).isEmpty();
        assertThat(get(a.load(new PlayerDoc(p)))).isEmpty();
    }

    // ---------- operations ----------

    @Test
    void incUsesDeclaredDefault() throws Exception {
        UUID p = UUID.randomUUID();
        ApplyResult r = get(a.apply(write(new PlayerDoc(p), inc(COINS, 5), inc(LEVEL, 2))));

        assertThat(r.applied()).isTrue();
        assertThat(decode(COINS, row(r.rows(), "contract").data())).isEqualTo(105L);
        assertThat(decode(LEVEL, row(r.rows(), "contract").data())).isEqualTo(3);
        assertThat(decode(COINS, row(get(a.load(new PlayerDoc(p))), "contract").data())).isEqualTo(105L);
    }

    @Test
    void withdrawExactlyOnceUnderConcurrency() throws Exception {
        UUID p = UUID.randomUUID(); // coins: default 100
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<ApplyResult>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Backend node = i % 2 == 0 ? a : b;
                results.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return node.apply(write(new PlayerDoc(p), withdraw(COINS, 10)));
                }, pool).thenCompose(f -> f));
            }
            start.countDown();
            int applied = 0;
            for (CompletableFuture<ApplyResult> f : results) {
                if (get(f).applied()) applied++;
            }
            assertThat(applied).isEqualTo(10);
            assertThat(decode(COINS, row(get(a.load(new PlayerDoc(p))), "contract").data())).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void withdrawFailsWithoutChange() throws Exception {
        UUID p = UUID.randomUUID();
        get(a.apply(write(new PlayerDoc(p), inc(COINS, 0))));
        long version = row(get(a.load(new PlayerDoc(p))), "contract").version();

        assertThat(get(a.apply(write(new PlayerDoc(p), withdraw(COINS, 101)))).applied()).isFalse();
        Row after = row(get(a.load(new PlayerDoc(p))), "contract");
        assertThat(decode(COINS, after.data())).isEqualTo(100L);
        assertThat(after.version()).isEqualTo(version);
    }

    @Test
    void idempotentReplayAppliesOnce() throws Exception {
        UUID p = UUID.randomUUID();
        WriteRequest request = write(new PlayerDoc(p), inc(COINS, 5));

        ApplyResult first = get(a.apply(request));
        ApplyResult replay = get(b.apply(request));

        assertThat(first.applied()).isTrue();
        assertThat(replay.applied()).isTrue();
        assertThat(row(replay.rows(), "contract").version()).isEqualTo(row(first.rows(), "contract").version());
        assertThat(decode(COINS, row(get(a.load(new PlayerDoc(p))), "contract").data())).isEqualTo(105L);
    }

    @Test
    void atomicAcrossNamespacesAllOrNothing() throws Exception {
        UUID p = UUID.randomUUID();
        ApplyResult ok = get(a.apply(write(new PlayerDoc(p), withdraw(COINS, 30), add(KITS, "vip"))));
        assertThat(ok.applied()).isTrue();
        assertThat(ok.rows()).extracting(r -> ((PlayerRow) r.ref()).namespace())
                .containsExactlyInAnyOrder("contract", "contract_kits");

        ApplyResult failed = get(a.apply(write(new PlayerDoc(p), add(KITS, "gold"), withdraw(COINS, 1000))));
        assertThat(failed.applied()).isFalse();

        WriteRequest unmet = new WriteRequest(key(), new PlayerDoc(p), List.of(add(KITS, "gold")),
                List.of(cond(COINS, new Predicate.Gt(encode(COINS, 1000L)))), Instant.now());
        assertThat(get(a.apply(unmet)).applied()).isFalse();

        List<Row> rows = get(a.load(new PlayerDoc(p)));
        assertThat(decode(COINS, row(rows, "contract").data())).isEqualTo(70L);
        assertThat(decode(KITS, row(rows, "contract_kits").data())).containsExactly("vip");
    }

    @Test
    void withdrawAfterNonAdditiveOpOnSameFieldRejected() {
        UUID p = UUID.randomUUID();
        WriteRequest r = write(new PlayerDoc(p), set(COINS, 50L), withdraw(COINS, 10));
        assertThat(failure(a.apply(r))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void foldedAmountsOverflowRejected() {
        UUID p = UUID.randomUUID();
        WriteRequest r = write(new PlayerDoc(p), new FieldOp.Inc(spec(COINS), Long.MIN_VALUE), withdraw(COINS, 1));
        assertThat(failure(a.apply(r))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void foldedWithdrawCountsMissingFieldWithoutDefaultAsZero() throws Exception {
        UUID p = UUID.randomUUID();
        ApplyResult r = get(a.apply(write(new PlayerDoc(p), new FieldOp.Inc(BARE, 5), new FieldOp.Withdraw(BARE, 3))));
        assertThat(r.applied()).isTrue();
        assertThat(((Number) row(r.rows(), "contract").data().get("bare")).longValue()).isEqualTo(2L);
    }

    @Test
    void transferMovesOrNothing() throws Exception {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        TransferResult ok = get(a.transfer(key(), new PlayerDoc(from), new PlayerDoc(to), spec(COINS), 40));
        assertThat(ok.transferred()).isTrue();
        assertThat(ok.rows()).hasSize(2);
        assertThat(coinsOf(from)).isEqualTo(60L);
        assertThat(coinsOf(to)).isEqualTo(140L);

        TransferResult no = get(a.transfer(key(), new PlayerDoc(from), new PlayerDoc(to), spec(COINS), 61));
        assertThat(no.transferred()).isFalse();
        assertThat(coinsOf(from)).isEqualTo(60L);
        assertThat(coinsOf(to)).isEqualTo(140L);
    }

    // ---------- create, unique ----------

    @Test
    void createIsExclusive() throws Exception {
        StoreDoc doc = new StoreDoc(newStore(), "spawn");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<CreateResult>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                long score = i;
                Backend node = i % 2 == 0 ? a : b;
                results.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return node.create(key(), doc, data(SCORE, score));
                }, pool).thenCompose(f -> f));
            }
            start.countDown();
            List<Long> winners = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                if (get(results.get(i)).created()) winners.add((long) i);
            }
            assertThat(winners).hasSize(1);
            assertThat(decode(SCORE, get(a.load(doc)).getFirst().data())).isEqualTo(winners.getFirst());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void uniqueViolationFailsWithDuplicateValueException() throws Exception {
        StoreRef store = newStore();
        get(a.declareIndex(new IndexDeclaration(new QueryTarget.StoreTarget(store), spec(OWNER), true)));
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        assertThat(get(a.create(key(), new StoreDoc(store, "d1"), data(OWNER, x))).created()).isTrue();

        assertThat(failure(b.create(key(), new StoreDoc(store, "d2"), data(OWNER, x))))
                .isInstanceOf(DuplicateValueException.class);
        assertThat(get(a.load(new StoreDoc(store, "d2")))).isEmpty();

        assertThat(get(a.create(key(), new StoreDoc(store, "d3"), data(OWNER, y))).created()).isTrue();
        assertThat(failure(b.apply(write(new StoreDoc(store, "d3"), set(OWNER, x)))))
                .isInstanceOf(DuplicateValueException.class);
        assertThat(decode(OWNER, get(a.load(new StoreDoc(store, "d3"))).getFirst().data())).isEqualTo(y);

        // documents without a value do not collide
        assertThat(get(a.create(key(), new StoreDoc(store, "e1"), Map.of())).created()).isTrue();
        assertThat(get(a.create(key(), new StoreDoc(store, "e2"), Map.of())).created()).isTrue();
    }

    /** R43: {@code unique} covers only explicitly set values; the default never collides, not even with itself. */
    @Test
    void uniqueAppliesOnlyToExplicitValues() throws Exception {
        StoreRef store = newStore();
        get(a.declareIndex(new IndexDeclaration(new QueryTarget.StoreTarget(store), spec(TAG), true)));
        StoreDoc e1 = new StoreDoc(store, "e1");
        StoreDoc e2 = new StoreDoc(store, "e2");
        StoreDoc e3 = new StoreDoc(store, "e3");
        // TAG has the default "": documents without the field do not collide with each other
        assertThat(get(a.create(key(), e1, Map.of())).created()).isTrue();
        assertThat(get(a.create(key(), e2, Map.of())).created()).isTrue();
        assertThat(get(a.create(key(), e3, Map.of())).created()).isTrue();

        // an explicit value equal to the default does not collide with documents without the field
        get(b.apply(write(e1, set(TAG, ""))));
        assertThat(decode(TAG, get(a.load(e1)).getFirst().data())).isEqualTo("");
        // a second explicit equal value does
        assertThat(failure(b.apply(write(e2, set(TAG, ""))))).isInstanceOf(DuplicateValueException.class);

        get(b.apply(write(e2, set(TAG, "red"))));
        assertThat(failure(b.apply(write(e3, set(TAG, "red"))))).isInstanceOf(DuplicateValueException.class);
        assertThat(get(a.load(e3)).getFirst().data()).doesNotContainKey(TAG.name());

        // declaring the index over documents without the field succeeds as well
        StoreRef later = newStore();
        get(a.create(key(), new StoreDoc(later, "x"), Map.of()));
        get(a.create(key(), new StoreDoc(later, "y"), Map.of()));
        get(a.declareIndex(new IndexDeclaration(new QueryTarget.StoreTarget(later), spec(TAG), true)));
    }

    @Test
    void declareUniqueOnDuplicatesFails() throws Exception {
        StoreRef store = newStore();
        UUID x = UUID.randomUUID();
        get(a.create(key(), new StoreDoc(store, "d1"), data(OWNER, x)));
        get(a.create(key(), new StoreDoc(store, "d2"), data(OWNER, x)));

        assertThat(failure(a.declareIndex(new IndexDeclaration(new QueryTarget.StoreTarget(store), spec(OWNER), true))))
                .isInstanceOf(DuplicateValueException.class);
        // the failed index does not reject further duplicates
        assertThat(get(a.create(key(), new StoreDoc(store, "d3"), data(OWNER, x))).created()).isTrue();
    }

    // ---------- expire, delete, watch ----------

    @Test
    void expiredDocIsNotLoadedOrQueried() throws Exception {
        StoreRef store = newStore();
        StoreDoc doc = new StoreDoc(store, "old");
        get(a.create(key(), doc, data(SCORE, 1L)));
        get(a.create(key(), new StoreDoc(store, "new"), data(SCORE, 1L)));

        get(a.expire(key(), doc, Instant.now().minusSeconds(1)));

        assertThat(get(a.load(doc))).isEmpty();
        QueryTarget target = new QueryTarget.StoreTarget(store);
        assertThat(ids(get(a.query(target, spec(List.of(), List.of(), 0, 0))))).containsExactly("new");
        assertThat(get(a.count(target, List.of()))).isEqualTo(1L);
        assertThat(get(a.versions(List.of(doc), List.of()))).containsEntry(doc, 0L);
    }

    @Test
    void deleteNotifiesWatchers() throws Exception {
        StoreRef store = newStore();
        StoreDoc doc = new StoreDoc(store, "d");
        List<DocChanged> events = collect(a);
        a.watchStore(store);
        long created = awaitWatched(store, events);

        assertThat(get(b.delete(key(), doc))).isTrue();
        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.ref().equals(doc) && e.deleted()));
        DocChanged deleted = events.stream().filter(e -> e.ref().equals(doc) && e.deleted()).findFirst().orElseThrow();
        assertThat(deleted.version()).isGreaterThan(created);
        assertThat(deleted.data()).isEmpty();
        assertThat(get(b.delete(key(), doc))).isFalse();
    }

    @Test
    void watchStoreReceivesChangesOfOtherNodes() throws Exception {
        StoreRef store = newStore();
        List<DocChanged> events = collect(a);
        a.watchStore(store);
        awaitWatched(store, events);

        StoreDoc doc = new StoreDoc(store, "clan");
        get(b.apply(write(doc, inc(SCORE, 7))));
        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.ref().equals(doc) && !e.deleted()
                && Long.valueOf(7L).equals(decode(SCORE, e.data()))));

        a.unwatchStore(store);
        StoreRef other = newStore();
        a.watchStore(other);
        awaitWatched(other, events);
        get(b.apply(write(doc, inc(SCORE, 1))));
        StoreDoc marker = new StoreDoc(other, "marker");
        get(b.apply(write(marker, inc(SCORE, 1))));
        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.ref().equals(marker)));
        assertThat(events).noneMatch(e -> e.ref().equals(doc) && Long.valueOf(8L).equals(decode(SCORE, e.data())));
    }

    // ---------- queries ----------

    @Test
    void queryUsesDefaultsForMissingFieldsAndRows() throws Exception {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        String tag = UUID.randomUUID().toString();
        get(a.apply(write(new PlayerDoc(p1), set(PlayerFields.NAME, tag + "1"), set(COINS, 150L))));
        get(a.apply(write(new PlayerDoc(p2), set(PlayerFields.NAME, tag + "2")))); // no contract row
        get(a.apply(write(new PlayerDoc(p3), set(PlayerFields.NAME, tag + "3"), set(LEVEL, 5)))); // no coins
        FieldCondition mine = cond(PlayerFields.NAME, new Predicate.In(List.of(tag + "1", tag + "2", tag + "3")));
        FieldCondition hundred = cond(COINS, new Predicate.Eq(encode(COINS, 100L)));

        List<DocSnapshot> hits = get(a.query(new QueryTarget.Players(), spec(List.of(mine, hundred), List.of(), 0, 0)));
        assertThat(hits).extracting(s -> ((PlayerDoc) s.doc()).player()).containsExactlyInAnyOrder(p2, p3);
        assertThat(get(a.count(new QueryTarget.Players(), List.of(mine, hundred)))).isEqualTo(2L);

        List<DocSnapshot> sorted = get(a.query(new QueryTarget.Players(),
                spec(List.of(mine), List.of(new SortSpec(spec(COINS), true)), 10, 0)));
        assertThat(sorted).hasSize(3);
        assertThat(((PlayerDoc) sorted.getFirst().doc()).player()).isEqualTo(p1);
        assertThat(sorted.getFirst().rows()).extracting(r -> ((PlayerRow) r.ref()).namespace())
                .containsExactlyInAnyOrder("contract", "pantheon");

        StoreRef store = newStore();
        get(a.create(key(), new StoreDoc(store, "rich"), data(COINS, 150L)));
        get(a.create(key(), new StoreDoc(store, "plain"), data(LEVEL, 2)));
        List<DocSnapshot> docs = get(a.query(new QueryTarget.StoreTarget(store), spec(List.of(hundred), List.of(), 0, 0)));
        assertThat(ids(docs)).containsExactly("plain");
    }

    @Test
    void playerQueryNeedsPantheonRow() throws Exception {
        UUID withoutBase = UUID.randomUUID();
        UUID withBase = UUID.randomUUID();
        String tag = UUID.randomUUID().toString();
        get(a.apply(write(new PlayerDoc(withoutBase), set(TAG, tag))));
        get(a.apply(write(new PlayerDoc(withBase), set(TAG, tag), set(PlayerFields.NAME, "n" + tag))));
        FieldCondition tagged = cond(TAG, new Predicate.Eq(encode(TAG, tag)));

        List<DocSnapshot> hits = get(a.query(new QueryTarget.Players(), spec(List.of(tagged), List.of(), 0, 0)));
        assertThat(hits).extracting(s -> ((PlayerDoc) s.doc()).player()).containsExactly(withBase);
        assertThat(get(a.count(new QueryTarget.Players(), List.of(tagged)))).isEqualTo(1L);
    }

    @Test
    void sortNullsLastAndTieBreakById() throws Exception {
        StoreRef store = newStore();
        Instant t0 = Instant.ofEpochMilli(1_700_000_000_000L);
        Instant t1 = t0.plusSeconds(60);
        get(a.create(key(), new StoreDoc(store, "b"), data(SEEN, t1)));
        get(a.create(key(), new StoreDoc(store, "B"), data(SEEN, t1)));
        get(a.create(key(), new StoreDoc(store, "a"), Map.of()));
        get(a.create(key(), new StoreDoc(store, "A"), Map.of()));
        get(a.create(key(), new StoreDoc(store, "d"), data(SEEN, t0)));
        QueryTarget target = new QueryTarget.StoreTarget(store);

        assertThat(ids(get(a.query(target, spec(List.of(), List.of(new SortSpec(spec(SEEN), true)), 0, 0)))))
                .containsExactly("B", "b", "d", "A", "a");
        assertThat(ids(get(a.query(target, spec(List.of(), List.of(new SortSpec(spec(SEEN), false)), 0, 0)))))
                .containsExactly("d", "B", "b", "A", "a");
        assertThat(ids(get(a.query(target, spec(List.of(), List.of(), 0, 0))))).containsExactly("A", "B", "a", "b", "d");
    }

    @Test
    void countMatchesQuery() throws Exception {
        StoreRef store = newStore();
        for (int i = 0; i < 6; i++) {
            get(a.create(key(), new StoreDoc(store, "d" + i), i % 3 == 0 ? Map.of() : data(COINS, 100L + i)));
        }
        QueryTarget target = new QueryTarget.StoreTarget(store);
        List<FieldCondition> where = List.of(cond(COINS, new Predicate.Gt(encode(COINS, 100L))));

        List<DocSnapshot> hits = get(a.query(target, spec(where, List.of(), 0, 0)));
        assertThat(ids(hits)).containsExactly("d1", "d2", "d4", "d5");
        assertThat(get(a.count(target, where))).isEqualTo(hits.size());
        assertThat(get(a.count(target, List.of()))).isEqualTo(6L);
    }

    @Test
    void offsetPagesWithoutGaps() throws Exception {
        StoreRef store = newStore();
        for (int i = 0; i < 25; i++) {
            get(a.create(key(), new StoreDoc(store, String.format("d%02d", i)), data(SCORE, (long) (i % 5))));
        }
        QueryTarget target = new QueryTarget.StoreTarget(store);
        List<SortSpec> sort = List.of(new SortSpec(spec(SCORE), true));

        List<String> all = ids(get(a.query(target, spec(List.of(), sort, 100, 0))));
        List<String> paged = new ArrayList<>();
        for (int offset = 0; offset < 30; offset += 10) {
            paged.addAll(ids(get(a.query(target, spec(List.of(), sort, 10, offset)))));
        }
        assertThat(all).hasSize(25);
        assertThat(paged).containsExactlyElementsOf(all);
        assertThat(new HashSet<>(paged)).hasSize(25);
        assertThat(failure(a.query(target, spec(List.of(), sort, 1001, 0)))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- versions ----------

    @Test
    void versionsZeroForMissing() throws Exception {
        StoreRef store = newStore();
        StoreDoc existing = new StoreDoc(store, "here");
        long version = get(a.create(key(), existing, data(SCORE, 1L))).row().version();
        StoreDoc missingDoc = new StoreDoc(store, "gone");
        PlayerRow missingRow = new PlayerRow(UUID.randomUUID(), "contract");

        Map<RowRef, Long> versions = get(a.versions(List.of(missingDoc, missingRow, existing), List.of()));
        assertThat(versions).containsEntry(missingDoc, 0L).containsEntry(missingRow, 0L)
                .containsEntry(existing, version);
    }

    @Test
    void versionsForPlayersReturnAllRows() throws Exception {
        UUID p = UUID.randomUUID();
        ApplyResult r = get(a.apply(write(new PlayerDoc(p), inc(COINS, 1), add(KITS, "starter"))));
        Map<RowRef, Long> expected = new LinkedHashMap<>();
        for (Row row : r.rows()) expected.put(row.ref(), row.version());

        Map<RowRef, Long> versions = get(b.versions(List.of(), List.of(p)));
        assertThat(versions).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(versions.keySet()).containsExactlyInAnyOrder(new PlayerRow(p, "contract"),
                new PlayerRow(p, "contract_kits"));
    }

    // ---------- events, values, validation ----------

    @Test
    void docChangedCarriesIdempotencyKey() throws Exception {
        StoreRef store = newStore();
        List<DocChanged> events = collect(a);
        a.watchStore(store);
        awaitWatched(store, events);

        StoreDoc doc = new StoreDoc(store, "keyed");
        WriteRequest request = write(doc, inc(SCORE, 3));
        get(b.apply(request));
        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.ref().equals(doc)));
        assertThat(events.stream().filter(e -> e.ref().equals(doc)).findFirst().orElseThrow().idempotencyKey())
                .isEqualTo(request.idempotencyKey());
    }

    @Test
    void longValuesStayExact() throws Exception {
        UUID p = UUID.randomUUID();
        long big = (1L << 53) + 1;
        get(a.apply(write(new PlayerDoc(p), set(COINS, big))));
        assertThat(coinsOf(p)).isEqualTo(big);

        get(a.apply(write(new PlayerDoc(p), set(COINS, Long.MAX_VALUE - 1))));
        ApplyResult r = get(a.apply(write(new PlayerDoc(p), inc(COINS, 1))));
        assertThat(decode(COINS, row(r.rows(), "contract").data())).isEqualTo(Long.MAX_VALUE);
        assertThat(coinsOf(p)).isEqualTo(Long.MAX_VALUE);
        assertThat(failure(a.apply(write(new PlayerDoc(p), inc(COINS, 1))))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storeFieldOfForeignNamespaceRejected() {
        StoreDoc doc = new StoreDoc(newStore(), "d");
        assertThat(failure(a.apply(write(doc, add(KITS, "vip"))))).isInstanceOf(IllegalArgumentException.class);
        assertThat(failure(a.query(new QueryTarget.StoreTarget(doc.store()),
                spec(List.of(cond(KITS, new Predicate.Exists())), List.of(), 0, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(failure(a.transfer(key(), doc, new StoreDoc(doc.store(), "e"), FieldSpecs.of(KITS_NS.ofLong("gems", 0)),
                1))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- idempotency of the other writes (R22) ----------

    @Test
    void createReplayReturnsStoredResult() throws Exception {
        StoreDoc doc = new StoreDoc(newStore(), "d");
        String k = key();
        CreateResult first = get(a.create(k, doc, data(SCORE, 1L)));
        CreateResult replay = get(b.create(k, doc, data(SCORE, 1L)));

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isTrue();
        assertThat(get(a.load(doc)).getFirst().version()).isEqualTo(first.row().version());
        assertThat(get(a.create(key(), doc, data(SCORE, 2L))).created()).isFalse();
    }

    @Test
    void deleteReplayDoesNotDeleteRecreatedDoc() throws Exception {
        StoreDoc doc = new StoreDoc(newStore(), "d");
        get(a.create(key(), doc, data(SCORE, 1L)));
        String k = key();
        assertThat(get(a.delete(k, doc))).isTrue();
        get(a.create(key(), doc, data(SCORE, 2L)));

        assertThat(get(b.delete(k, doc))).isTrue();
        assertThat(decode(SCORE, get(a.load(doc)).getFirst().data())).isEqualTo(2L);
    }

    @Test
    void expireReplayDoesNotExpireRecreatedDoc() throws Exception {
        StoreDoc doc = new StoreDoc(newStore(), "d");
        get(a.create(key(), doc, data(SCORE, 1L)));
        String k = key();
        Instant past = Instant.now().minusSeconds(1);
        get(a.expire(k, doc, past));
        assertThat(get(a.load(doc))).isEmpty();
        get(a.create(key(), doc, data(SCORE, 2L)));

        get(b.expire(k, doc, past));
        assertThat(get(a.load(doc))).hasSize(1);
    }

    @Test
    void transferReplayMovesOnce() throws Exception {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        String k = key();
        assertThat(get(a.transfer(k, new PlayerDoc(from), new PlayerDoc(to), spec(COINS), 40)).transferred()).isTrue();
        assertThat(get(b.transfer(k, new PlayerDoc(from), new PlayerDoc(to), spec(COINS), 40)).transferred()).isTrue();
        assertThat(coinsOf(from)).isEqualTo(60L);
        assertThat(coinsOf(to)).isEqualTo(140L);
    }

    // ---------- writes over expired, not yet deleted documents (R41) ----------

    @Test
    void createOverExpiredDocSucceeds() throws Exception {
        StoreDoc doc = expiredDoc(data(SCORE, 1L));
        assertThat(get(b.create(key(), doc, data(SCORE, 2L))).created()).isTrue();
        assertThat(decode(SCORE, get(a.load(doc)).getFirst().data())).isEqualTo(2L);
    }

    @Test
    void applyOnExpiredDocStartsFromDefaults() throws Exception {
        StoreDoc doc = expiredDoc(Map.of(SCORE.name(), 5L, COINS.name(), 150L));
        ApplyResult r = get(b.apply(write(doc, inc(SCORE, 1))));
        assertThat(r.applied()).isTrue();
        Map<String, Object> data = r.rows().getFirst().data();
        assertThat(decode(SCORE, data)).isEqualTo(1L);
        assertThat(decode(COINS, data)).isEqualTo(100L);
        List<Row> loaded = get(a.load(doc)); // the new document does not inherit the expiry
        assertThat(loaded).hasSize(1);
        assertThat(decode(SCORE, loaded.getFirst().data())).isEqualTo(1L);
    }

    @Test
    void transferFromExpiredDocStartsFromDefaults() throws Exception {
        StoreDoc from = expiredDoc(data(COINS, 10L));
        StoreDoc to = new StoreDoc(from.store(), "to");
        assertThat(get(b.transfer(key(), from, to, spec(COINS), 50)).transferred()).isTrue();
        assertThat(decode(COINS, get(a.load(from)).getFirst().data())).isEqualTo(50L);
        assertThat(decode(COINS, get(a.load(to)).getFirst().data())).isEqualTo(150L);
    }

    @Test
    void deleteOfExpiredDocReturnsFalse() throws Exception {
        StoreDoc doc = expiredDoc(data(SCORE, 1L));
        assertThat(get(b.delete(key(), doc))).isFalse();
    }

    @Test
    void uniqueIgnoresExpiredDoc() throws Exception {
        StoreRef store = newStore();
        get(a.declareIndex(new IndexDeclaration(new QueryTarget.StoreTarget(store), spec(OWNER), true)));
        UUID x = UUID.randomUUID();
        StoreDoc old = new StoreDoc(store, "old");
        get(a.create(key(), old, data(OWNER, x)));
        get(a.expire(key(), old, Instant.now().minusSeconds(1)));

        assertThat(get(b.create(key(), new StoreDoc(store, "new"), data(OWNER, x))).created()).isTrue();
        StoreDoc third = new StoreDoc(store, "third");
        get(b.create(key(), third, Map.of()));
        assertThat(failure(b.apply(write(third, set(OWNER, x))))).isInstanceOf(DuplicateValueException.class);
    }

    /** A document whose expiry is already past; the cleanup job may or may not have deleted it yet. */
    private StoreDoc expiredDoc(Map<String, Object> data) throws Exception {
        StoreDoc doc = new StoreDoc(newStore(), "expired");
        get(a.create(key(), doc, data));
        get(a.expire(key(), doc, Instant.now().minusSeconds(1)));
        return doc;
    }

    // ---------- outage ----------

    @Test
    void outageFailsCallsWithDataUnavailable() throws Exception {
        UUID p = UUID.randomUUID();
        get(a.apply(write(new PlayerDoc(p), inc(COINS, 1))));
        fx.outage();
        try {
            long start = System.nanoTime();
            Throwable load = failure(a.load(new PlayerDoc(p)));
            Throwable apply = failure(a.apply(write(new PlayerDoc(p), withdraw(COINS, 1))));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
            assertThat(load).isInstanceOf(DataUnavailableException.class);
            assertThat(apply).isInstanceOf(DataUnavailableException.class);
        } finally {
            fx.restore();
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> a.state() == ConnectionState.CONNECTED);
        await().atMost(WAIT).ignoreExceptions().until(() -> coinsOf(p) == 101L);
    }

    // ---------- helpers ----------

    /** Waits until the watch is active: writes a marker document until its event arrives; returns its version. */
    private long awaitWatched(StoreRef store, List<DocChanged> events) throws Exception {
        StoreDoc marker = new StoreDoc(store, "d");
        get(b.create(key(), marker, Map.of()));
        Optional<DocChanged> seen = Optional.empty();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (seen.isEmpty() && System.nanoTime() < deadline) {
            get(b.apply(write(marker, inc(SCORE, 0))));
            long round = System.nanoTime() + Duration.ofMillis(500).toNanos();
            while (seen.isEmpty() && System.nanoTime() < round) {
                seen = events.stream().filter(e -> e.ref().equals(marker)).reduce((x, y) -> y);
                if (seen.isEmpty()) Thread.sleep(10);
            }
        }
        assertThat(seen).as("watch on %s active", store.qualified()).isPresent();
        events.removeIf(e -> e.ref().equals(marker));
        return get(b.load(marker)).getFirst().version();
    }

    private static List<DocChanged> collect(Backend node) {
        List<DocChanged> events = new CopyOnWriteArrayList<>();
        node.onDocChanged(events::add);
        return events;
    }

    private long coinsOf(UUID player) throws Exception {
        List<Row> rows = get(a.load(new PlayerDoc(player)));
        return decode(COINS, rows.stream().filter(r -> ((PlayerRow) r.ref()).namespace().equals("contract"))
                .findFirst().map(Row::data).orElse(Map.of()));
    }

    protected static StoreRef newStore() {
        return new StoreRef("contract", "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    }

    /** An idempotency key shaped like the real ones: UUIDv7 (48 bit millis, version 7, variant 10, random). */
    protected static String key() {
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        long msb = (System.currentTimeMillis() << 16) | 0x7000L | (rnd.nextLong() & 0x0FFFL);
        long lsb = (rnd.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    protected static FieldSpec spec(Field<?> field) {
        return FieldSpecs.of(field);
    }

    protected static WriteRequest write(DocRef doc, FieldOp... ops) {
        return new WriteRequest(key(), doc, List.of(ops), List.of(), Instant.now());
    }

    protected static QuerySpec spec(List<FieldCondition> where, List<SortSpec> sort, int limit, int offset) {
        return new QuerySpec(where, sort, limit, offset);
    }

    protected static FieldOp inc(Field<?> f, long delta) {
        return new FieldOp.Inc(spec(f), delta);
    }

    protected static FieldOp withdraw(Field<?> f, long amount) {
        return new FieldOp.Withdraw(spec(f), amount);
    }

    protected static <T> FieldOp set(Field<T> f, T value) {
        return new FieldOp.Set(spec(f), encode(f, value));
    }

    protected static <E> FieldOp add(Field<Set<E>> f, E element) {
        Object wire = encode(f, Set.of(element));
        return new FieldOp.Add(spec(f), ((List<?>) wire).getFirst());
    }

    protected static FieldCondition cond(Field<?> f, Predicate p) {
        return new FieldCondition(spec(f), p);
    }

    protected static <T> Object encode(Field<T> f, T value) {
        return FieldCodecs.forField(f).encode(value);
    }

    protected static <T> Map<String, Object> data(Field<T> f, T value) {
        return Map.of(f.name(), encode(f, value));
    }

    protected static <T> T decode(Field<T> f, Map<String, Object> data) {
        FieldCodec<T> codec = FieldCodecs.forField(f);
        return codec.decode(data.get(f.name()));
    }

    protected static Row row(List<Row> rows, String namespace) {
        return rows.stream().filter(r -> r.ref() instanceof PlayerRow pr && pr.namespace().equals(namespace))
                .findFirst().orElseThrow(() -> new AssertionError("no row " + namespace + " in " + rows));
    }

    protected static List<String> ids(List<DocSnapshot> docs) {
        return docs.stream().map(s -> ((StoreDoc) s.doc()).id()).toList();
    }

    protected static <T> T get(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    /** The exception the future fails with. */
    protected static Throwable failure(CompletableFuture<?> future) {
        try {
            future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException | TimeoutException e) {
            throw new AssertionError("future did not fail in time", e);
        }
        throw new AssertionError("future completed normally");
    }
}
