package de.kalypzo.pantheon.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.data.DocHandle;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.data.UuidV7;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.IndexDeclaration;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.QueryTarget;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.core.backend.DataBackend;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.CreateResult;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.DocSnapshot;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.QuerySpec;
import de.kalypzo.pantheon.core.data.wire.RowRef;
import de.kalypzo.pantheon.core.data.wire.TransferResult;
import de.kalypzo.pantheon.data.DataUnavailableException;
import de.kalypzo.pantheon.data.Subscription;
import java.nio.file.Path;
import java.util.Collection;
import java.util.function.Consumer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Replaying the outbox after a reconnect (P-14; agora.md §8 Nachholen, E13). */
class OutboxDrainerTest {

    private static final FieldSpec COINS = new FieldSpec("economy", "coins", ScalarType.LONG, 100L);
    private static final FieldSpec TAG = new FieldSpec("economy", "tag", ScalarType.STRING, "");
    private static final FieldSpec CLAN_TAG = new FieldSpec("lobby", "tag", ScalarType.STRING, "");

    private final DocRef alice = new PlayerDoc(UUID.fromString("00000000-0000-0000-0000-00000000000a"));

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final InMemoryNetwork network = new InMemoryNetwork(clock);
    private final InMemoryNetwork.Node node = network.node("paper-1");
    private final List<LogRecord> logs = new CopyOnWriteArrayList<>();
    private final Logger log = Logger.getLogger("OutboxDrainerTest." + UUID.randomUUID());
    private Outbox outbox;
    private DocWriter writer;
    private OutboxDrainer drainer;

    @BeforeEach
    void setUp() {
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        outbox = Outbox.open(dir.resolve("outbox.db"), clock, log);
        writer = new DocWriter(node, outbox, clock, Runnable::run, log);
        drainer = new OutboxDrainer(outbox, node, writer, clock, scheduler, log);
    }

    @AfterEach
    void tearDown() {
        drainer.close();
        outbox.close();
        network.close();
    }

    private Map<String, Object> stored(DocRef doc, String namespace) {
        for (Row row : node.load(doc).join()) {
            if (row.ref() instanceof de.kalypzo.pantheon.core.data.wire.PlayerRow p && p.namespace().equals(namespace)) {
                return row.data();
            }
            if (row.ref() instanceof StoreDoc) return row.data();
        }
        return Map.of();
    }

    @Test
    void drainerReplaysInOrderAfterReconnect() {
        DocHandle handle = writer.acquire(alice);
        network.outage();
        writer.write(alice, List.of(new FieldOp.Inc(COINS, 1), new FieldOp.Set(TAG, "a")));
        writer.write(alice, List.of(new FieldOp.Inc(COINS, 1), new FieldOp.Set(TAG, "b")));
        writer.write(alice, List.of(new FieldOp.Inc(COINS, 1), new FieldOp.Set(TAG, "c")));
        assertThat(outbox.pendingByDoc().get(Outbox.docKey(alice))).hasSize(3);
        assertThat(handle.view("economy")).containsEntry("coins", 103L);

        network.restore();
        CompletableFuture<Void> done = drainer.drain();
        scheduler.runDue();

        assertThat(done).isCompleted();
        assertThat(outbox.pendingByDoc()).isEmpty();
        assertThat(outbox.hasEntries(Outbox.docKey(alice))).isFalse();
        assertThat(stored(alice, "economy")).containsEntry("coins", 103L).containsEntry("tag", "c");
        assertThat(handle.view("economy")).containsEntry("coins", 103L).containsEntry("tag", "c");
        assertThat(handle.pending()).isEmpty();
    }

    @Test
    void replayOfAlreadyAppliedRequestIsNotDoubled() {
        WriteRequest request = new WriteRequest(UuidV7.next(clock).toString(), alice,
                List.of(new FieldOp.Inc(COINS, 5)), List.of(), clock.instant());
        node.apply(request).join();                       // it did arrive before the crash
        outbox.append(Outbox.docKey(alice), request);     // but was never confirmed

        CompletableFuture<Void> done = drainer.drain();
        scheduler.runDue();

        assertThat(done).isCompleted();
        assertThat(outbox.pendingByDoc()).isEmpty();
        assertThat(stored(alice, "economy")).containsEntry("coins", 105L);
    }

    @Test
    void backoffWhileUnavailable() {
        network.outage();
        WriteRequest request = new WriteRequest(UuidV7.next(clock).toString(), alice,
                List.of(new FieldOp.Inc(COINS, 1)), List.of(), clock.instant());
        outbox.append(Outbox.docKey(alice), request);

        CompletableFuture<Void> done = drainer.drain();
        scheduler.runDue();
        assertThat(done).isNotDone();
        assertThat(scheduler.oneShotDelaysMillis).containsExactly(1_000L);

        for (long d : new long[] {1_000, 2_000, 4_000, 8_000, 16_000, 30_000}) {
            scheduler.advance(Duration.ofMillis(d));
            assertThat(done).isNotDone();
        }
        assertThat(scheduler.oneShotDelaysMillis)
                .containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L);
        assertThat(outbox.hasEntries(Outbox.docKey(alice))).isTrue();

        network.restore();
        scheduler.advance(Duration.ofMillis(30_000));

        assertThat(done).isCompleted();
        assertThat(outbox.pendingByDoc()).isEmpty();
        assertThat(stored(alice, "economy")).containsEntry("coins", 101L);
    }

    @Test
    void connectedDuringBackoffRetriesAtOnce() {
        network.outage();
        outbox.append(Outbox.docKey(alice), new WriteRequest(UuidV7.next(clock).toString(), alice,
                List.of(new FieldOp.Inc(COINS, 1)), List.of(), clock.instant()));
        CompletableFuture<Void> first = drainer.drain();
        scheduler.runDue();
        assertThat(first).isNotDone();

        network.restore();
        CompletableFuture<Void> second = drainer.drain(); // CONNECTED
        scheduler.runDue();

        assertThat(first).isCompleted();
        assertThat(second).isCompleted();
        assertThat(stored(alice, "economy")).containsEntry("coins", 101L);
        assertThat(scheduler.pendingOneShots()).isZero();
    }

    @Test
    void conditionalWaitingBehindOutboxIsSentAfterReplay() {
        writer.acquire(alice);
        network.outage();
        writer.write(alice, List.of(new FieldOp.Inc(COINS, 50)));
        network.restore();
        CompletableFuture<de.kalypzo.pantheon.core.data.wire.ApplyResult> withdraw = writer.writeConditional(alice,
                List.of(new FieldOp.Withdraw(COINS, 120)), List.of());
        assertThat(withdraw).isNotDone(); // waits for the outbox (Review-Fokus 4)

        drainer.drain();
        scheduler.runDue();

        assertThat(withdraw).isCompleted();
        assertThat(withdraw.join().applied()).isTrue();
        assertThat(stored(alice, "economy")).containsEntry("coins", 30L);
    }

    @Test
    void failedSendWhileConnectedIsReplayedWithoutReconnect() {
        FlakyBackend flaky = new FlakyBackend(node);
        DocWriter direct = new DocWriter(flaky, node::state, outbox, clock, Runnable::run, log);
        try (OutboxDrainer own = new OutboxDrainer(outbox, flaky, direct, clock, scheduler, log)) {
            DocHandle handle = direct.acquire(alice);
            flaky.failNext = 1; // e.g. a deadline: the connection stays CONNECTED, no drain() comes
            direct.write(alice, List.of(new FieldOp.Inc(COINS, 1)));
            assertThat(node.state()).isEqualTo(de.kalypzo.pantheon.core.backend.ConnectionState.CONNECTED);
            assertThat(outbox.hasEntries(Outbox.docKey(alice))).isTrue();

            scheduler.runDue(); // the append woke the drainer

            assertThat(outbox.pendingByDoc()).isEmpty();
            assertThat(stored(alice, "economy")).containsEntry("coins", 101L);
            assertThat(handle.pending()).isEmpty();
            direct.write(alice, List.of(new FieldOp.Inc(COINS, 1))); // direct again
            assertThat(outbox.pendingByDoc()).isEmpty();
            assertThat(stored(alice, "economy")).containsEntry("coins", 102L);
            assertThat(direct.writeConditional(alice, List.of(new FieldOp.Withdraw(COINS, 2)), List.of()))
                    .isCompletedWithValueMatching(de.kalypzo.pantheon.core.data.wire.ApplyResult::applied);
        }
    }

    @Test
    void minuteSweepReplaysUndrainedDocs() {
        assertThat(drainer.drain()).isCompleted(); // empty outbox: done at once, the minute task runs from now on
        outbox.append(Outbox.docKey(alice), new WriteRequest(UuidV7.next(clock).toString(), alice,
                List.of(new FieldOp.Inc(COINS, 1)), List.of(), clock.instant())); // nobody wakes the drainer

        scheduler.advance(Duration.ofMinutes(1));

        assertThat(outbox.pendingByDoc()).isEmpty();
        assertThat(stored(alice, "economy")).containsEntry("coins", 101L);
    }

    @Test
    void entryAt19h59IsSent() {
        outbox.append(Outbox.docKey(alice), new WriteRequest(UuidV7.next(clock).toString(), alice,
                List.of(new FieldOp.Inc(COINS, 1)), List.of(), clock.instant()));
        clock.advance(Duration.ofHours(19).plusMinutes(59));

        drainer.drain();
        scheduler.runDue();

        assertThat(outbox.pendingByDoc()).isEmpty();
        assertThat(stored(alice, "economy")).containsEntry("coins", 101L);
    }

    @Test
    void expiredEntryIsRejectedLocally() {
        DocHandle handle = writer.acquire(alice);
        network.outage();
        writer.write(alice, List.of(new FieldOp.Inc(COINS, 1)));
        assertThat(handle.view("economy")).containsEntry("coins", 101L);

        clock.advance(Duration.ofHours(20).plusMinutes(1));
        network.restore();
        CompletableFuture<Void> done = drainer.drain();
        scheduler.runDue();

        assertThat(done).isCompleted();
        assertThat(outbox.pendingByDoc()).isEmpty();
        assertThat(handle.pending()).isEmpty();
        assertThat(handle.view("economy")).doesNotContainEntry("coins", 101L);
        assertThat(stored(alice, "economy")).doesNotContainKey("coins");
        assertThat(logs).anyMatch(r -> r.getLevel() == Level.SEVERE);
    }

    @Test
    void replayRejectedByUniqueRevertsLocally() {
        StoreRef clans = new StoreRef("lobby", "clans");
        node.declareIndex(new IndexDeclaration(new QueryTarget.StoreTarget(clans), CLAN_TAG, true)).join();
        StoreDoc red = new StoreDoc(clans, "red");
        StoreDoc blue = new StoreDoc(clans, "blue");
        node.create(UuidV7.next(clock).toString(), red, Map.of()).join();
        node.create(UuidV7.next(clock).toString(), blue, Map.of()).join();
        DocHandle handle = writer.acquire(blue);

        network.outage();
        writer.write(blue, List.of(new FieldOp.Set(CLAN_TAG, "x")));
        assertThat(handle.view("lobby")).containsEntry("tag", "x");
        network.restore();
        node.apply(new WriteRequest(UuidV7.next(clock).toString(), red, List.of(new FieldOp.Set(CLAN_TAG, "x")),
                List.of(), clock.instant())).join(); // someone else took the tag meanwhile

        CompletableFuture<Void> done = drainer.drain();
        scheduler.runDue();

        assertThat(done).isCompleted();
        assertThat(outbox.pendingByDoc()).isEmpty();
        assertThat(handle.pending()).isEmpty();
        assertThat(handle.view("lobby")).doesNotContainEntry("tag", "x");
        assertThat(logs).anyMatch(r -> r.getLevel() == Level.SEVERE);
    }

    /** Delegates to the node; fails the next {@code failNext} applies with {@link DataUnavailableException}. */
    private static final class FlakyBackend implements DataBackend {
        private final DataBackend delegate;
        volatile int failNext;

        FlakyBackend(DataBackend delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<ApplyResult> apply(WriteRequest request) {
            if (failNext > 0) {
                failNext--;
                return CompletableFuture.failedFuture(new DataUnavailableException("deadline exceeded"));
            }
            return delegate.apply(request);
        }

        @Override
        public CompletableFuture<List<Row>> loadPlayer(UUID player) {
            return delegate.loadPlayer(player);
        }

        @Override
        public void releasePlayer(UUID player) {
            delegate.releasePlayer(player);
        }

        @Override
        public CompletableFuture<List<Row>> load(DocRef doc) {
            return delegate.load(doc);
        }

        @Override
        public CompletableFuture<List<DocSnapshot>> query(QueryTarget target, QuerySpec spec) {
            return delegate.query(target, spec);
        }

        @Override
        public CompletableFuture<Long> count(QueryTarget target, List<FieldCondition> where) {
            return delegate.count(target, where);
        }

        @Override
        public CompletableFuture<Map<RowRef, Long>> versions(Collection<RowRef> rows, Collection<UUID> players) {
            return delegate.versions(rows, players);
        }

        @Override
        public CompletableFuture<CreateResult> create(String idempotencyKey, StoreDoc doc, Map<String, Object> data) {
            return delegate.create(idempotencyKey, doc, data);
        }

        @Override
        public CompletableFuture<Boolean> delete(String idempotencyKey, StoreDoc doc) {
            return delegate.delete(idempotencyKey, doc);
        }

        @Override
        public CompletableFuture<Void> expire(String idempotencyKey, StoreDoc doc, Instant at) {
            return delegate.expire(idempotencyKey, doc, at);
        }

        @Override
        public CompletableFuture<TransferResult> transfer(String idempotencyKey, DocRef from, DocRef to,
                                                          FieldSpec field, long amount) {
            return delegate.transfer(idempotencyKey, from, to, field, amount);
        }

        @Override
        public CompletableFuture<Void> declareIndex(IndexDeclaration index) {
            return delegate.declareIndex(index);
        }

        @Override
        public void watchStore(StoreRef store) {
            delegate.watchStore(store);
        }

        @Override
        public void unwatchStore(StoreRef store) {
            delegate.unwatchStore(store);
        }

        @Override
        public Subscription onDocChanged(Consumer<DocChanged> listener) {
            return delegate.onDocChanged(listener);
        }
    }
}
