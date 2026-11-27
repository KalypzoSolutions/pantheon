package de.kalypzo.pantheon.core.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.core.backend.ConnectionState;
import de.kalypzo.pantheon.core.backend.DataBackend;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
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
import de.kalypzo.pantheon.core.data.wire.QuerySpec;
import de.kalypzo.pantheon.core.data.wire.QueryTarget;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.RowRef;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.data.wire.TransferResult;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.core.outbox.Outbox;
import de.kalypzo.pantheon.core.outbox.OutboxEntry;
import de.kalypzo.pantheon.data.DataUnavailableException;
import de.kalypzo.pantheon.data.DuplicateValueException;
import de.kalypzo.pantheon.data.Subscription;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The write pipeline (P-13; agora.md §5 Reihenfolge, data.md §3, E13, E27, Review-Fokus 4). */
class DocWriterTest {

    private static final FieldSpec COINS = new FieldSpec("economy", "coins", ScalarType.LONG, 100L);
    private static final FieldSpec KILLS = new FieldSpec("economy", "kills", ScalarType.LONG, 0L);
    private static final FieldSpec TAG = new FieldSpec("economy", "tag", ScalarType.STRING, "");

    private final DocRef alice = new PlayerDoc(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private final DocRef bob = new PlayerDoc(UUID.fromString("00000000-0000-0000-0000-00000000000b"));

    private final FakeBackend backend = new FakeBackend();
    private final FakeOutbox outbox = new FakeOutbox();
    private final AtomicReference<ConnectionState> connection = new AtomicReference<>(ConnectionState.CONNECTED);
    private final List<LogRecord> logs = new CopyOnWriteArrayList<>();
    private final Logger log = Logger.getLogger("DocWriterTest." + UUID.randomUUID());
    private DocWriter writer;

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
        writer = new DocWriter(backend, connection::get, outbox, Clock.systemUTC(), Runnable::run, log);
    }

    @AfterEach
    void tearDown() {
        backend.network.close();
    }

    @Test
    void onlyOneRequestInFlightPerDoc() {
        writer.write(alice, List.of(inc(COINS, 1)));
        writer.write(alice, List.of(inc(KILLS, 1)));
        writer.write(bob, List.of(inc(COINS, 1)));

        assertThat(backend.calls).hasSize(2);
        assertThat(backend.calls).extracting(c -> c.request().doc()).containsExactly(alice, bob);

        backend.ack(0);
        assertThat(backend.calls).hasSize(3);
        assertThat(backend.calls.get(2).request().doc()).isEqualTo(alice);
        assertThat(backend.calls.get(2).request().ops()).containsExactly(inc(KILLS, 1));
    }

    @Test
    void opsDuringFlightAreBatchedInOrder() {
        writer.write(alice, List.of(set(TAG, "a")));
        writer.write(alice, List.of(inc(COINS, 1)));
        writer.write(alice, List.of(set(TAG, "b"), inc(KILLS, 2)));
        writer.write(alice, List.of(new FieldOp.Unset(TAG)));
        assertThat(backend.calls).hasSize(1);

        backend.ack(0);

        assertThat(backend.calls).hasSize(2);
        WriteRequest second = backend.calls.get(1).request();
        assertThat(second.ops()).containsExactly(inc(COINS, 1), set(TAG, "b"), inc(KILLS, 2), new FieldOp.Unset(TAG));
        assertThat(second.idempotencyKey()).isNotEqualTo(backend.calls.get(0).request().idempotencyKey());
        assertThat(second.conditions()).isEmpty();
    }

    @Test
    void adjacentIncsAreCoalesced() {
        DocHandle handle = writer.acquire(alice);
        writer.write(alice, List.of(set(TAG, "x")));
        writer.write(alice, List.of(inc(COINS, 1)));
        writer.write(alice, List.of(inc(COINS, 1)));
        writer.write(alice, List.of(inc(COINS, 1)));
        assertThat(handle.view("economy")).containsEntry("coins", 103L);

        backend.forward(0);
        assertThat(backend.calls.get(1).request().ops()).containsExactly(inc(COINS, 3));

        backend.forward(1);
        assertThat(handle.pending()).isEmpty();
        assertThat(handle.view("economy")).containsEntry("coins", 103L).containsEntry("tag", "x");
    }

    @Test
    void conditionalIsOwnRequestAfterPrecedingOps() throws Exception {
        DocHandle handle = writer.acquire(alice);
        writer.write(alice, List.of(inc(COINS, 50)));             // 100 + 50, still in flight
        CompletableFuture<ApplyResult> withdraw =
                writer.writeConditional(alice, List.of(new FieldOp.Withdraw(COINS, 120)), List.of());

        assertThat(backend.calls).hasSize(1);                       // the withdraw waits behind the inc
        assertThat(withdraw).isNotDone();

        backend.forward(0);                                         // the inc arrives in the database
        assertThat(backend.calls).hasSize(2);
        WriteRequest conditional = backend.calls.get(1).request();
        assertThat(conditional.ops()).containsExactly(new FieldOp.Withdraw(COINS, 120));
        assertThat(conditional.idempotencyKey()).isNotEqualTo(backend.calls.get(0).request().idempotencyKey());

        backend.forward(1);
        assertThat(get(withdraw).applied()).isTrue();
        assertThat(handle.view("economy")).containsEntry("coins", 30L);
    }

    @Test
    void conditionalIsNeverBatchedWithLaterOps() {
        writer.write(alice, List.of(inc(COINS, 1)));
        writer.writeConditional(alice, List.of(new FieldOp.Withdraw(COINS, 1)), List.of());
        writer.write(alice, List.of(inc(KILLS, 1)));

        backend.ack(0);
        assertThat(backend.calls.get(1).request().ops()).containsExactly(new FieldOp.Withdraw(COINS, 1));
        backend.ack(1);
        assertThat(backend.calls.get(2).request().ops()).containsExactly(inc(KILLS, 1));
    }

    @Test
    void conditionalFailsImmediatelyWhenDisconnected() {
        connection.set(ConnectionState.DISCONNECTED);

        CompletableFuture<ApplyResult> result =
                writer.writeConditional(alice, List.of(new FieldOp.Withdraw(COINS, 1)), List.of());
        CompletableFuture<TransferResult> transfer = writer.transfer(alice, bob, COINS, 1);

        assertThat(failureNow(result)).isInstanceOf(DataUnavailableException.class);
        assertThat(failureNow(transfer)).isInstanceOf(DataUnavailableException.class);
        assertThat(backend.calls).isEmpty();
        assertThat(outbox.entries).isEmpty();
    }

    @Test
    void conditionalFailsAfter5sWhenOutboxIsNotDrained() {
        writer.write(alice, List.of(inc(COINS, 1)));
        backend.fail(0, new DataUnavailableException("down"));
        assertThat(outbox.hasEntries(Outbox.docKey(alice))).isTrue();

        long start = System.nanoTime();
        CompletableFuture<ApplyResult> result =
                writer.writeConditional(alice, List.of(new FieldOp.Withdraw(COINS, 1)), List.of());
        assertThat(result).isNotDone();

        Throwable failure = failure(result, Duration.ofSeconds(10));
        assertThat(failure).isInstanceOf(DataUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(4500),
                Duration.ofSeconds(9));
        assertThat(backend.calls).hasSize(1);
    }

    @Test
    void conditionalRunsOnceOutboxIsDrained() throws Exception {
        writer.write(alice, List.of(inc(COINS, 1)));
        backend.fail(0, new DataUnavailableException("down"));
        CompletableFuture<ApplyResult> result =
                writer.writeConditional(alice, List.of(new FieldOp.Withdraw(COINS, 1)), List.of());

        OutboxEntry entry = outbox.entries.getFirst();
        outbox.remove(entry.idempotencyKey());
        writer.outboxAcked(entry.idempotencyKey(), new ApplyResult(true, List.of()));

        assertThat(backend.calls).hasSize(2);
        backend.ack(1);
        assertThat(get(result).applied()).isTrue();
    }

    @Test
    void failedRequestGoesToOutboxWithSameKey() {
        DocHandle handle = writer.acquire(alice);
        writer.write(alice, List.of(inc(COINS, 5)));
        WriteRequest sent = backend.calls.getFirst().request();

        backend.fail(0, new DataUnavailableException("deadline exceeded"));

        assertThat(outbox.entries).hasSize(1);
        OutboxEntry entry = outbox.entries.getFirst();
        assertThat(entry.idempotencyKey()).isEqualTo(sent.idempotencyKey());
        assertThat(entry.docKey()).isEqualTo("player:00000000-0000-0000-0000-00000000000a");
        assertThat(entry.request()).isEqualTo(sent);
        assertThat(handle.view("economy")).containsEntry("coins", 105L);   // stays visible until the drainer acks

        writer.outboxAcked(sent.idempotencyKey(), new ApplyResult(true, List.of(
                new Row(new de.kalypzo.pantheon.core.data.wire.PlayerRow(
                        ((PlayerDoc) alice).player(), "economy"), 3, Map.of("coins", 105L)))));
        assertThat(handle.pending()).isEmpty();
        assertThat(handle.view("economy")).containsEntry("coins", 105L);
    }

    @Test
    void laterOpsQueueBehindOutboxEntries() {
        writer.write(alice, List.of(inc(COINS, 1)));
        writer.write(alice, List.of(inc(COINS, 2)));
        writer.write(alice, List.of(set(TAG, "b")));

        backend.fail(0, new DataUnavailableException("down"));

        assertThat(outbox.entries).extracting(e -> e.request().ops()).containsExactly(
                List.of(inc(COINS, 1)), List.of(inc(COINS, 2), set(TAG, "b")));
        writer.write(alice, List.of(inc(KILLS, 1)));
        assertThat(outbox.entries).hasSize(3);
        assertThat(outbox.entries.getLast().request().ops()).containsExactly(inc(KILLS, 1));
        assertThat(backend.calls).hasSize(1);                       // nothing overtakes the outbox
        assertThat(outbox.entries).extracting(OutboxEntry::docKey).containsOnly(Outbox.docKey(alice));

        for (OutboxEntry e : List.copyOf(outbox.entries)) {
            outbox.remove(e.idempotencyKey());
            writer.outboxAcked(e.idempotencyKey(), new ApplyResult(true, List.of()));
        }
        writer.write(alice, List.of(inc(KILLS, 1)));
        assertThat(backend.calls).hasSize(2);
    }

    @Test
    void transferWaitsBehindSenderOps() throws Exception {
        writer.write(alice, List.of(inc(COINS, 50)));
        CompletableFuture<TransferResult> transfer = writer.transfer(alice, bob, COINS, 120);
        assertThat(backend.calls).hasSize(1);
        assertThat(backend.transfers).isEmpty();

        backend.forward(0);
        assertThat(backend.transfers).hasSize(1);
        backend.forwardTransfer(0);

        assertThat(get(transfer).transferred()).isTrue();
    }

    @Test
    void transferUpdatesBothAcquiredHandles() throws Exception {
        DocHandle from = writer.acquire(alice);
        DocHandle to = writer.acquire(bob);

        assertThat(get(forwarded(writer.transfer(alice, bob, COINS, 40), 0)).transferred()).isTrue();

        assertThat(from.view("economy")).containsEntry("coins", 60L);
        assertThat(to.view("economy")).containsEntry("coins", 140L);
    }

    @Test
    void flushWaitsThenMovesRestToOutbox() throws Exception {
        writer.write(alice, List.of(inc(COINS, 1)));                // hangs
        writer.write(alice, List.of(inc(KILLS, 1)));                // queued behind it
        CompletableFuture<ApplyResult> conditional =
                writer.writeConditional(alice, List.of(new FieldOp.Withdraw(COINS, 1)), List.of());

        long start = System.nanoTime();
        writer.flush(Duration.ofMillis(200)).get(2, TimeUnit.SECONDS);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(190));

        assertThat(outbox.entries).extracting(OutboxEntry::idempotencyKey)
                .first().isEqualTo(backend.calls.getFirst().request().idempotencyKey());
        assertThat(outbox.entries).extracting(e -> e.request().ops())
                .containsExactly(List.of(inc(COINS, 1)), List.of(inc(KILLS, 1)));
        assertThat(failureNow(conditional)).isInstanceOf(DataUnavailableException.class);

        backend.fail(0, new DataUnavailableException("closed"));    // a late failure does not append twice
        assertThat(outbox.entries).hasSize(2);
    }

    @Test
    void flushCompletesAtOnceWhenIdle() throws Exception {
        writer.write(alice, List.of(inc(COINS, 1)));
        backend.ack(0);

        writer.flush(Duration.ofSeconds(10)).get(1, TimeUnit.SECONDS);
        assertThat(outbox.entries).isEmpty();
    }

    @Test
    void uniqueViolationOnSetRevertsLocalValue() {
        List<List<RowDiff>> diffs = new CopyOnWriteArrayList<>();
        writer.onDiff((ref, d) -> diffs.add(d));
        DocHandle handle = writer.acquire(alice);
        writer.write(alice, List.of(set(TAG, "taken")));
        assertThat(handle.view("economy")).containsEntry("tag", "taken");

        backend.fail(0, new DuplicateValueException("economy_tag"));

        assertThat(handle.view("economy")).doesNotContainKey("tag");
        assertThat(handle.pending()).isEmpty();
        assertThat(outbox.entries).isEmpty();                        // no retry (E27)
        assertThat(diffs).hasSize(1);
        assertThat(diffs.getFirst().getFirst().after()).doesNotContainKey("tag");
        assertThat(logs).anySatisfy(r -> {
            assertThat(r.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(r.getMessage()).contains("economy_tag");
        });

        writer.write(alice, List.of(inc(COINS, 1)));                // the pipeline goes on
        assertThat(backend.calls).hasSize(2);
    }

    @Test
    void releasedHandleKeepsRunningUntilIdle() {
        DocHandle handle = writer.acquire(alice);
        assertThat(writer.acquire(alice)).isSameAs(handle);
        writer.release(alice);
        writer.write(alice, List.of(inc(COINS, 1)));
        writer.write(alice, List.of(inc(COINS, 1)));
        writer.release(alice);

        assertThat(writer.idle(alice)).isFalse();
        assertThat(writer.acquire(alice)).isSameAs(handle);         // still alive
        writer.release(alice);

        backend.ack(0);
        assertThat(writer.idle(alice)).isFalse();
        backend.ack(1);
        assertThat(handle.pending()).isEmpty();
        assertThat(writer.idle(alice)).isTrue();
        assertThat(writer.acquire(alice)).isNotSameAs(handle);      // dropped once idle
    }

    @Test
    void invalidOpThrowsAndSendsNothing() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> writer.write(alice, List.of(new FieldOp.Inc(TAG, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(backend.calls).isEmpty();
    }

    @Test
    void writeRejectsWithdraw() {
        DocHandle handle = writer.acquire(alice);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> writer.write(alice, List.of(inc(KILLS, 1), new FieldOp.Withdraw(COINS, 1))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(handle.pending()).isEmpty();
        assertThat(backend.calls).isEmpty();
    }

    @Test
    void notAppliedUnconditionalBatchIsRejected() {
        DocHandle handle = writer.acquire(alice);
        writer.write(alice, List.of(inc(COINS, 5)));

        backend.calls.getFirst().result().complete(new ApplyResult(false, List.of()));

        assertThat(handle.pending()).isEmpty();
        assertThat(handle.view("economy")).doesNotContainKey("coins");
        assertThat(outbox.entries).isEmpty();
        assertThat(logs).anySatisfy(r -> assertThat(r.getLevel()).isEqualTo(Level.SEVERE));
    }

    @Test
    void rejectedBatchIsSplitAndOnlyOffendingCallReverted() {
        DocHandle handle = writer.acquire(alice);
        writer.write(alice, List.of(set(TAG, "x")));                // in flight
        writer.write(alice, List.of(inc(COINS, 5)));                // call A
        writer.write(alice, List.of(set(TAG, "taken")));            // call B, violates unique
        backend.forward(0);
        WriteRequest batch = backend.calls.get(1).request();
        assertThat(batch.ops()).containsExactly(inc(COINS, 5), set(TAG, "taken"));

        backend.fail(1, new DuplicateValueException("economy_tag"));

        assertThat(backend.calls).hasSize(3);                       // one call at a time, in order
        WriteRequest a = backend.calls.get(2).request();
        assertThat(a.ops()).containsExactly(inc(COINS, 5));
        assertThat(a.idempotencyKey()).isNotEqualTo(batch.idempotencyKey());
        writer.write(alice, List.of(inc(KILLS, 1)));                // queued behind the split calls
        backend.forward(2);

        assertThat(backend.calls).hasSize(4);
        WriteRequest b = backend.calls.get(3).request();
        assertThat(b.ops()).containsExactly(set(TAG, "taken"));
        assertThat(b.idempotencyKey()).isNotIn(batch.idempotencyKey(), a.idempotencyKey());
        backend.fail(3, new DuplicateValueException("economy_tag"));

        assertThat(backend.calls.get(4).request().ops()).containsExactly(inc(KILLS, 1));
        backend.forward(4);
        assertThat(handle.pending()).isEmpty();
        assertThat(handle.view("economy")).containsEntry("coins", 105L).containsEntry("tag", "x")
                .containsEntry("kills", 1L);
        assertThat(outbox.entries).isEmpty();
        assertThat(logs).filteredOn(r -> r.getLevel() == Level.SEVERE).hasSize(1);
    }

    @Test
    void splitPartsAreCoalescedAndRespect500Ops() {
        DocHandle handle = writer.acquire(alice);
        writer.write(alice, List.of(set(TAG, "x")));                // in flight
        List<FieldOp> incs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) incs.add(inc(COINS, 1));
        writer.write(alice, incs);                                  // call A
        writer.write(alice, List.of(set(TAG, "taken")));            // call B
        backend.forward(0);
        assertThat(backend.calls.get(1).request().ops()).containsExactly(inc(COINS, 1000), set(TAG, "taken"));

        backend.fail(1, new DuplicateValueException("economy_tag"));

        assertThat(backend.calls.get(2).request().ops()).containsExactly(inc(COINS, 1000));
        backend.forward(2);
        backend.fail(3, new DuplicateValueException("economy_tag"));
        assertThat(handle.pending()).isEmpty();
        assertThat(handle.view("economy")).containsEntry("coins", 1100L).containsEntry("tag", "x");
    }

    @Test
    void invalidBatchIsSplitAndOnlyInvalidCallDropped() {
        DocHandle handle = writer.acquire(alice);
        writer.write(alice, List.of(set(TAG, "x")));                // in flight
        writer.write(alice, List.of(inc(COINS, 5)));                // call A
        writer.write(alice, List.of(set(TAG, "y")));                // call B, rejected by the backend
        backend.forward(0);

        backend.fail(1, new IllegalArgumentException("invalid"));
        assertThat(backend.calls.get(2).request().ops()).containsExactly(inc(COINS, 5));
        backend.forward(2);
        assertThat(backend.calls.get(3).request().ops()).containsExactly(set(TAG, "y"));
        backend.fail(3, new IllegalArgumentException("invalid"));

        assertThat(backend.calls).hasSize(4);
        assertThat(handle.pending()).isEmpty();
        assertThat(handle.view("economy")).containsEntry("coins", 105L).containsEntry("tag", "x");
        assertThat(logs).filteredOn(r -> r.getLevel() == Level.SEVERE).hasSize(1);
    }

    @Test
    void batchAlreadyInOutboxIsNotSplit() throws Exception {
        DocHandle handle = writer.acquire(alice);
        writer.write(alice, List.of(set(TAG, "x")));
        writer.write(alice, List.of(inc(COINS, 5)));
        writer.write(alice, List.of(set(TAG, "taken")));
        backend.forward(0);
        String key = backend.calls.get(1).request().idempotencyKey();
        writer.flush(Duration.ofMillis(50)).get(2, TimeUnit.SECONDS);
        assertThat(outbox.entries).extracting(OutboxEntry::idempotencyKey).containsExactly(key);

        backend.fail(1, new DuplicateValueException("economy_tag"));

        assertThat(backend.calls).hasSize(2);                       // no resend under new keys: no double apply
        assertThat(outbox.entries).extracting(OutboxEntry::idempotencyKey).containsExactly(key);
        assertThat(handle.pending()).isEmpty();
        assertThat(handle.view("economy")).containsEntry("tag", "x");
        assertThat(logs).anySatisfy(r -> assertThat(r.getLevel()).isEqualTo(Level.SEVERE));
    }

    @Test
    void flushedEnqueueFailsOutsideTheLock() throws Exception {
        writer.flush(Duration.ofMillis(10)).get(1, TimeUnit.SECONDS);
        CompletableFuture<ApplyResult> r =
                writer.writeConditional(alice, List.of(new FieldOp.Withdraw(COINS, 1)), List.of());
        assertThat(failureNow(r)).isInstanceOf(DataUnavailableException.class);
    }

    @Test
    void atMost500OpsPerRequest() {
        writer.write(alice, List.of(set(TAG, "first")));
        List<FieldOp> many = new ArrayList<>();
        for (int i = 0; i < 600; i++) many.add(set(TAG, "v" + i));
        writer.write(alice, many);

        backend.ack(0);
        assertThat(backend.calls.get(1).request().ops()).hasSize(500);
        backend.ack(1);
        assertThat(backend.calls.get(2).request().ops()).hasSize(100);
    }

    // ---------- helpers ----------

    private static FieldOp inc(FieldSpec f, long delta) {
        return new FieldOp.Inc(f, delta);
    }

    private static FieldOp set(FieldSpec f, Object value) {
        return new FieldOp.Set(f, value);
    }

    private <T> CompletableFuture<T> forwarded(CompletableFuture<T> future, int transferIndex) {
        backend.forwardTransfer(transferIndex);
        return future;
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private static Throwable failureNow(CompletableFuture<?> future) {
        assertThat(future).isCompletedExceptionally();
        return failure(future, Duration.ZERO);
    }

    private static Throwable failure(CompletableFuture<?> future, Duration max) {
        try {
            future.get(Math.max(1, max.toMillis()), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new AssertionError("future did not fail in time", e);
        }
        throw new AssertionError("future completed normally");
    }

    /** Apply/transfer calls wait until the test completes them; {@code forward} runs them on an in-memory network. */
    private static final class FakeBackend implements DataBackend {

        record Call<R>(WriteRequest request, CompletableFuture<R> result) {}

        record TransferCall(String key, DocRef from, DocRef to, FieldSpec field, long amount,
                            CompletableFuture<TransferResult> result) {}

        final InMemoryNetwork network = new InMemoryNetwork();
        final DataBackend real = network.node("fake");
        final List<Call<ApplyResult>> calls = new CopyOnWriteArrayList<>();
        final List<TransferCall> transfers = new CopyOnWriteArrayList<>();

        void ack(int i) {
            Call<ApplyResult> c = calls.get(i);
            c.result().complete(new ApplyResult(true, List.of()));
        }

        void forward(int i) {
            Call<ApplyResult> c = calls.get(i);
            real.apply(c.request()).whenComplete((r, e) -> {
                if (e != null) c.result().completeExceptionally(e);
                else c.result().complete(r);
            });
        }

        void forwardTransfer(int i) {
            await().atMost(Duration.ofSeconds(5)).until(() -> transfers.size() > i);
            TransferCall c = transfers.get(i);
            real.transfer(c.key(), c.from(), c.to(), c.field(), c.amount()).whenComplete((r, e) -> {
                if (e != null) c.result().completeExceptionally(e);
                else c.result().complete(r);
            });
        }

        void fail(int i, Throwable error) {
            calls.get(i).result().completeExceptionally(error);
        }

        @Override
        public CompletableFuture<ApplyResult> apply(WriteRequest request) {
            CompletableFuture<ApplyResult> f = new CompletableFuture<>();
            calls.add(new Call<>(request, f));
            return f;
        }

        @Override
        public CompletableFuture<TransferResult> transfer(String idempotencyKey, DocRef from, DocRef to,
                                                          FieldSpec field, long amount) {
            CompletableFuture<TransferResult> f = new CompletableFuture<>();
            transfers.add(new TransferCall(idempotencyKey, from, to, field, amount, f));
            return f;
        }

        @Override
        public CompletableFuture<List<Row>> loadPlayer(UUID player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releasePlayer(UUID player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<List<Row>> load(DocRef doc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<List<DocSnapshot>> query(QueryTarget target, QuerySpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Long> count(QueryTarget target, List<FieldCondition> where) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Map<RowRef, Long>> versions(Collection<RowRef> rows, Collection<UUID> players) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<CreateResult> create(String idempotencyKey, StoreDoc doc, Map<String, Object> data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Boolean> delete(String idempotencyKey, StoreDoc doc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> expire(String idempotencyKey, StoreDoc doc, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> declareIndex(IndexDeclaration index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void watchStore(StoreRef store) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void unwatchStore(StoreRef store) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Subscription onDocChanged(Consumer<DocChanged> listener) {
            throw new UnsupportedOperationException();
        }
    }

    /** The outbox in memory (the SQLite one comes with P-14). */
    private static final class FakeOutbox implements Outbox {

        final List<OutboxEntry> entries = new CopyOnWriteArrayList<>();
        private long seq;

        @Override
        public synchronized void append(String docKey, WriteRequest request) {
            if (request.conditional()) throw new IllegalArgumentException("conditional request");
            entries.add(new OutboxEntry(++seq, request.idempotencyKey(), docKey, request, request.createdAt()));
        }

        @Override
        public boolean hasEntries(String docKey) {
            return entries.stream().anyMatch(e -> e.docKey().equals(docKey));
        }

        @Override
        public Map<String, List<OutboxEntry>> pendingByDoc() {
            Map<String, List<OutboxEntry>> out = new LinkedHashMap<>();
            for (OutboxEntry e : entries) out.computeIfAbsent(e.docKey(), k -> new ArrayList<>()).add(e);
            return out;
        }

        @Override
        public void remove(String idempotencyKey) {
            entries.removeIf(e -> e.idempotencyKey().equals(idempotencyKey));
        }

        @Override
        public int moveExpired(Duration maxAge, Consumer<OutboxEntry> onDead) {
            return 0;
        }

        @Override
        public void close() {}
    }
}
