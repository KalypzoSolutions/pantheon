package de.kalypzo.pantheon.core.data;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.ConnectionState;
import de.kalypzo.pantheon.core.backend.DataBackend;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.TransferResult;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.core.outbox.Outbox;
import de.kalypzo.pantheon.data.DataUnavailableException;
import de.kalypzo.pantheon.data.DuplicateValueException;
import de.kalypzo.pantheon.data.Subscription;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The write pipeline (agora.md §5 "Reihenfolge pro Dokument", data.md §3, E13, E27). Per document at most one request
 * is in flight; what is called meanwhile collects in call order (at most {@value #MAX_OPS} ops per request, adjacent
 * {@code Inc} on the same field coalesced) and leaves as the next request. A batch gets its idempotency key when it is
 * closed and is never changed afterwards. Conditional requests and transfers are never batched and queue behind the
 * ops called before them (transfers in the sender's queue).
 *
 * <p>A failing unconditional request goes to the outbox with its key, as do all ops of the document collected after
 * it; while the outbox has entries for a document, new unconditional ops go straight into it. A unique violation
 * ({@link DuplicateValueException}) or an invalid request (IAE) is not retried: the ops are dropped, the local value
 * reverts and an ERROR is logged.
 *
 * <p>Diffs that arise later (ack, rejection) reach the {@link #onDiff} listeners on {@code callbackExecutor}; the
 * diffs of {@link #write} are returned to the caller. Requests are sent on the calling or completing thread, never
 * on {@code callbackExecutor}. Thread-safe.
 */
public final class DocWriter {

    /** At most this many ops per request. */
    public static final int MAX_OPS = 500;
    /** A conditional request waits at most this long for the document's outbox entries to be replayed. */
    public static final Duration OUTBOX_WAIT = Duration.ofSeconds(5);

    private final DataBackend backend;
    private final Supplier<ConnectionState> connection;
    private final Outbox outbox;
    private final Clock clock;
    private final Executor callbackExecutor;
    private final Logger log;

    private final Object lock = new Object();
    private final Map<DocRef, DocState> docs = new HashMap<>();              // guarded by lock
    private final Map<String, DocState> byOutboxKey = new HashMap<>();       // guarded by lock
    private final List<CompletableFuture<Void>> idleWaiters = new ArrayList<>(); // guarded by lock
    private boolean flushed;                                                 // guarded by lock
    private final List<BiConsumer<DocRef, List<RowDiff>>> listeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> appendListeners = new CopyOnWriteArrayList<>();

    /** Checks {@link Backend#state()} for conditional requests (R6). */
    public DocWriter(Backend backend, Outbox outbox, Clock clock, Executor callbackExecutor, Logger log) {
        this(backend, backend::state, outbox, clock, callbackExecutor, log);
    }

    public DocWriter(DataBackend backend, Supplier<ConnectionState> connection, Outbox outbox, Clock clock,
                     Executor callbackExecutor, Logger log) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** The handle of the document; counted, it lives at least as long as it is acquired or ops are open. */
    public DocHandle acquire(DocRef ref) {
        synchronized (lock) {
            DocState s = state(ref);
            s.refs++;
            return s.handle;
        }
    }

    public void release(DocRef ref) {
        synchronized (lock) {
            DocState s = docs.get(ref);
            if (s == null) return;
            if (s.refs > 0) s.refs--;
            dropIfUnused(s);
        }
    }

    /** Diffs from acks and rejections, on {@code callbackExecutor}. */
    public Subscription onDiff(BiConsumer<DocRef, List<RowDiff>> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Called with the doc key after every request this writer wrote to the outbox, outside the writer's lock on the
     * writing thread (the drainer's wakeup, E13); must return quickly.
     */
    public Subscription onOutboxAppend(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
        appendListeners.add(listener);
        return () -> appendListeners.remove(listener);
    }

    /** Unconditional: visible at once, sent in the background. Throws an IAE for an invalid op; nothing changes then. */
    public List<RowDiff> write(DocRef ref, List<FieldOp> ops) {
        Objects.requireNonNull(ref, "ref");
        List<FieldOp> copy = List.copyOf(ops);
        if (copy.isEmpty()) return List.of();
        List<Runnable> after = new ArrayList<>();
        List<RowDiff> diffs;
        synchronized (lock) {
            DocState s = state(ref);
            try {
                for (FieldOp op : copy) {
                    if (op instanceof FieldOp.Withdraw) {
                        throw new IllegalArgumentException("withdraw only exists in conditional requests: " + op);
                    }
                }
                diffs = s.handle.addPending(copy);
            } catch (RuntimeException e) {
                dropIfUnused(s);
                throw e;
            }
            long call = ++s.calls;
            for (FieldOp op : copy) append(s, op, call);
            pump(s, after);
        }
        run(after);
        return diffs;
    }

    /** Its own request, behind the ops called before it. Fails at once if the backend is not connected. */
    public CompletableFuture<ApplyResult> writeConditional(DocRef ref, List<FieldOp> ops,
                                                           List<FieldCondition> conditions) {
        Objects.requireNonNull(ref, "ref");
        List<FieldOp> opsCopy = List.copyOf(ops);
        List<FieldCondition> conditionsCopy = List.copyOf(conditions);
        CompletableFuture<ApplyResult> result = new CompletableFuture<>();
        enqueue(ref, result, () -> new Conditional(
                new WriteRequest(newKey(), ref, opsCopy, conditionsCopy, clock.instant()), result));
        return result;
    }

    /** Withdraws from {@code from}, credits {@code to}, in one transaction; queued in the sender's queue. */
    public CompletableFuture<TransferResult> transfer(DocRef from, DocRef to, FieldSpec field, long amount) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(field, "field");
        CompletableFuture<TransferResult> result = new CompletableFuture<>();
        enqueue(from, result, () -> new Transfer(newKey(), to, field, amount, result));
        return result;
    }

    /**
     * R46: a backend call on one document (create, delete, expire) that runs behind the ops called before it and
     * before the ops called after it. {@code call} gets a fresh idempotency key. Like a conditional request it is
     * never batched, fails at once if the backend is not connected and is not written to the outbox.
     */
    public <T> CompletableFuture<T> enqueueCall(DocRef ref, java.util.function.Function<String, CompletableFuture<T>> call) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(call, "call");
        CompletableFuture<T> result = new CompletableFuture<>();
        enqueue(ref, result, () -> new Direct<>(newKey(), call, result));
        return result;
    }

    /** The drainer (P-14) replayed an outbox entry: its ops leave {@code pending}, the rows are adopted. */
    public void outboxAcked(String idempotencyKey, ApplyResult result) {
        List<Runnable> after = new ArrayList<>();
        synchronized (lock) {
            DocState s = byOutboxKey.remove(idempotencyKey);
            if (s != null) {
                s.outboxKeys.remove(idempotencyKey);
                ack(s, idempotencyKey, result.rows(), after);
                pump(s, after);
                dropIfUnused(s);
            } else { // an entry from before a restart: update whoever holds the rows
                for (Map.Entry<DocRef, List<Row>> e : rowsByDoc(result.rows()).entrySet()) {
                    DocState other = docs.get(e.getKey());
                    if (other == null) continue;
                    dispatch(other.ref, other.handle.acked(idempotencyKey, e.getValue()), after);
                    pump(other, after);
                }
            }
        }
        run(after);
    }

    /**
     * The drainer (P-14) gave up on an outbox entry (unique violation E27, invalid request, {@code applied == false},
     * or older than 20 h and moved to {@code outbox_dead}): its ops are dropped and the local value reverts. The entry
     * must already have left the outbox. An entry from before a restart has no local ops; nothing happens then.
     */
    public void outboxRejected(String idempotencyKey) {
        List<Runnable> after = new ArrayList<>();
        synchronized (lock) {
            DocState s = byOutboxKey.remove(idempotencyKey);
            if (s == null) return;
            s.outboxKeys.remove(idempotencyKey);
            dispatch(s.ref, s.handle.rejected(idempotencyKey), after);
            pump(s, after);
            dropIfUnused(s);
        }
        run(after);
    }

    /**
     * Shutdown: waits at most {@code max} until every document is idle, then writes the rest to the outbox. Requests
     * still in flight go there with their key as well (a late ack is harmless, idempotency); queued conditional
     * requests fail with {@link DataUnavailableException}. Afterwards every unconditional op goes straight to the
     * outbox.
     *
     * <p>Known ordering exception at shutdown: if a conditional request or transfer is still in flight when the budget
     * ends, the batches queued behind it go to the outbox at once. A drainer that replays them while the conditional
     * is still unanswered can apply them before it. Keeping them in memory instead would lose them, because the backend
     * and the outbox are closed right after the flush (P-26).
     */
    public CompletableFuture<Void> flush(Duration max) {
        CompletableFuture<Void> idle = new CompletableFuture<>();
        synchronized (lock) {
            if (allIdle()) idle.complete(null);
            else idleWaiters.add(idle);
        }
        return idle.completeOnTimeout(null, max.toMillis(), TimeUnit.MILLISECONDS).thenRun(this::moveRestToOutbox);
    }

    /**
     * Shutdown, right before the outbox closes (P-26): writes the rest to the outbox now unless {@link #flush} has
     * done so already. It runs under the writer lock, like every outbox write of this writer, so it waits for a move
     * that is still running (a flush whose timeout fired late or whose outbox I/O is slow), and afterwards nothing
     * queued is left to be moved onto a closed outbox. Idempotent; writes called after it are logged as lost.
     */
    public void moveRestToOutboxNow() {
        moveRestToOutbox();
    }

    /** Nothing of the document is queued or in flight (outbox entries do not count). */
    public boolean idle(DocRef ref) {
        synchronized (lock) {
            DocState s = docs.get(ref);
            return s == null || s.idle();
        }
    }

    // ---------- queue ----------

    private <T> void enqueue(DocRef ref, CompletableFuture<T> result, Supplier<Item> item) {
        if (connection.get() != ConnectionState.CONNECTED) {
            result.completeExceptionally(new DataUnavailableException("backend is not connected"));
            return;
        }
        List<Runnable> after = new ArrayList<>();
        synchronized (lock) {
            if (flushed) {
                after.add(() -> result.completeExceptionally(new DataUnavailableException("shutting down")));
            } else {
                DocState s = state(ref);
                if (s.queue.peekLast() instanceof Batch b) close(s, b); // the conditional never joins a batch
                s.queue.addLast(item.get());
                pump(s, after);
            }
        }
        run(after);
    }

    private void append(DocState s, FieldOp op, long call) {
        if (s.queue.peekLast() instanceof Batch b && b.request == null) {
            if (b.coalesce(op)) {
                b.addRaw(op, call);
                return;
            }
            if (b.ops.size() < MAX_OPS) {
                b.ops.add(op);
                b.addRaw(op, call);
                return;
            }
            close(s, b);
        }
        Batch next = new Batch();
        next.ops.add(op);
        next.addRaw(op, call);
        s.queue.addLast(next);
    }

    /** Gives the batch its key; from here on the request never changes. */
    private void close(DocState s, Batch b) {
        if (b.request != null) return;
        b.request = new WriteRequest(newKey(), s.ref, b.ops, List.of(), clock.instant());
        List<PendingOp> sent = new ArrayList<>(b.raw.size());
        for (FieldOp op : b.raw) sent.add(new PendingOp(op, null));
        s.handle.markSent(b.request.idempotencyKey(), sent);
    }

    /** Sends the head of the queue if nothing is in flight; collects the work to run outside the lock. */
    private void pump(DocState s, List<Runnable> after) {
        // after flush nothing is sent any more: the queue goes to the outbox even behind a request in flight
        while ((s.inFlight == null || flushed) && !s.queue.isEmpty()) {
            Item head = s.queue.peekFirst();
            boolean outboxed = flushed || outbox.hasEntries(Outbox.docKey(s.ref));
            switch (head) {
                case Batch b -> {
                    close(s, b);
                    s.queue.pollFirst();
                    if (outboxed) {
                        toOutbox(s, b.request, after);
                    } else {
                        s.inFlight = b;
                        after.add(() -> send(s, b));
                    }
                }
                case Conditional c -> {
                    Start start = start(s, c, outboxed, c.result, after);
                    if (start == Start.WAIT) return;
                    if (start == Start.SEND) after.add(() -> send(s, c));
                }
                case Transfer t -> {
                    Start start = start(s, t, outboxed, t.result, after);
                    if (start == Start.WAIT) return;
                    if (start == Start.SEND) after.add(() -> send(s, t));
                }
                case Direct<?> d -> {
                    Start start = start(s, d, outboxed, d.result, after);
                    if (start == Start.WAIT) return;
                    if (start == Start.SEND) after.add(() -> send(s, d));
                }
            }
        }
        if (s.idle()) {
            dropIfUnused(s);
            if (!idleWaiters.isEmpty() && allIdle()) {
                List<CompletableFuture<Void>> waiters = List.copyOf(idleWaiters);
                idleWaiters.clear();
                after.add(() -> waiters.forEach(w -> w.complete(null)));
            }
        }
    }

    private enum Start { SEND, FAILED, WAIT }

    /** Starts a conditional head: in flight ({@code SEND}), failed at shutdown, or waiting for the outbox. */
    private Start start(DocState s, Item item, boolean outboxed, CompletableFuture<?> result, List<Runnable> after) {
        if (flushed) {
            s.queue.pollFirst();
            after.add(() -> result.completeExceptionally(new DataUnavailableException("shutting down")));
            return Start.FAILED;
        }
        if (outboxed) {
            if (!item.waiting) {
                item.waiting = true;
                CompletableFuture.delayedExecutor(OUTBOX_WAIT.toMillis(), TimeUnit.MILLISECONDS)
                        .execute(() -> waitTimedOut(s, item, result));
            }
            return Start.WAIT;
        }
        s.queue.pollFirst();
        s.inFlight = item;
        return Start.SEND;
    }

    private void waitTimedOut(DocState s, Item item, CompletableFuture<?> result) {
        List<Runnable> after = new ArrayList<>();
        synchronized (lock) {
            if (s.queue.peekFirst() != item || s.inFlight != null) return; // sent or failed meanwhile
            s.queue.pollFirst();
            after.add(() -> result.completeExceptionally(new DataUnavailableException(
                    "outbox of " + Outbox.docKey(s.ref) + " not replayed within " + OUTBOX_WAIT.toSeconds() + " s")));
            pump(s, after);
        }
        run(after);
    }

    // ---------- sending ----------

    private void send(DocState s, Batch b) {
        call(() -> backend.apply(b.request)).whenComplete((r, e) -> batchDone(s, b, r, e));
    }

    private void send(DocState s, Conditional c) {
        call(() -> backend.apply(c.request)).whenComplete((r, e) -> {
            List<Runnable> after = new ArrayList<>();
            synchronized (lock) {
                s.inFlight = null;
                if (e == null && r.applied()) ack(s, c.request.idempotencyKey(), r.rows(), after);
                pump(s, after);
            }
            after.addFirst(() -> {
                if (e != null) c.result.completeExceptionally(unwrap(e));
                else c.result.complete(r);
            });
            run(after);
        });
    }

    private <T> void send(DocState s, Direct<T> d) {
        call(() -> d.call.apply(d.key)).whenComplete((r, e) -> {
            List<Runnable> after = new ArrayList<>();
            synchronized (lock) {
                s.inFlight = null;
                pump(s, after);
            }
            after.addFirst(() -> {
                if (e != null) d.result.completeExceptionally(unwrap(e));
                else d.result.complete(r);
            });
            run(after);
        });
    }

    private void send(DocState s, Transfer t) {
        call(() -> backend.transfer(t.key, s.ref, t.to, t.field, t.amount)).whenComplete((r, e) -> {
            List<Runnable> after = new ArrayList<>();
            synchronized (lock) {
                s.inFlight = null;
                if (e == null && r.transferred()) {
                    for (Map.Entry<DocRef, List<Row>> rows : rowsByDoc(r.rows()).entrySet()) {
                        DocState holder = docs.get(rows.getKey());
                        if (holder != null) dispatch(holder.ref, holder.handle.acked(t.key, rows.getValue()), after);
                    }
                }
                pump(s, after);
            }
            after.addFirst(() -> {
                if (e != null) t.result.completeExceptionally(unwrap(e));
                else t.result.complete(r);
            });
            run(after);
        });
    }

    private void batchDone(DocState s, Batch b, @Nullable ApplyResult r, @Nullable Throwable e) {
        List<Runnable> after = new ArrayList<>();
        synchronized (lock) {
            s.inFlight = null;
            boolean alreadyInOutbox = s.inFlightInOutbox;
            s.inFlightInOutbox = false;
            String key = b.request.idempotencyKey();
            Throwable cause = e == null ? null : unwrap(e);
            if (e == null && r.applied()) {
                ack(s, key, r.rows(), after);
            } else if (cause == null || cause instanceof DuplicateValueException
                    || cause instanceof IllegalArgumentException) {
                if (b.calls.size() > 1 && !alreadyInOutbox) {
                    split(s, b); // R44: only the offending call is dropped
                } else {
                    // already in the outbox (flush): no split, the drainer owns the entry; resending its calls under
                    // new keys could apply them twice if the drainer replays the entry as well
                    if (alreadyInOutbox && b.calls.size() == 1) removeFromOutbox(s, key);
                    String reason = switch (cause) {
                        case null -> "was not applied by the backend";
                        case DuplicateValueException dup -> "violates unique index " + dup.index() + " (E27)";
                        default -> "was rejected as invalid";
                    };
                    log.log(Level.SEVERE, "Write to " + Outbox.docKey(s.ref) + " " + reason + " and is dropped: "
                            + b.request.ops(), cause);
                    dispatch(s.ref, s.handle.rejected(key), after);
                }
            } else {
                if (!alreadyInOutbox) {
                    log.log(Level.FINE, "Write to " + Outbox.docKey(s.ref) + " failed, moved to the outbox", cause);
                    toOutbox(s, b.request, after);
                }
            }
            pump(s, after);
        }
        run(after);
    }

    /**
     * Resends a rejected batch one write call at a time, each with a fresh key (the rejected request was never applied,
     * so a new key cannot double anything), at the front of the queue in the original order (R44). Parts are coalesced
     * and chunked to at most {@value #MAX_OPS} ops like normal batches. A batch that {@link #flush} already wrote to
     * the outbox is never split: the drainer may replay that entry, and resending its calls under new keys could
     * apply them twice; it is only reverted locally.
     */
    private void split(DocState s, Batch b) {
        s.handle.unmark(b.request.idempotencyKey());
        List<Batch> parts = new ArrayList<>(b.calls.size());
        for (List<FieldOp> callOps : b.calls) {
            Batch part = new Batch();
            parts.add(part);
            for (FieldOp op : callOps) { // coalesced and chunked like normal batching
                if (!part.coalesce(op)) {
                    if (part.ops.size() >= MAX_OPS) {
                        part = new Batch();
                        parts.add(part);
                    }
                    part.ops.add(op);
                }
                part.addRaw(op, 0);
            }
        }
        // close in order: each part's ops are then the earliest unsent pending ops of the handle
        for (Batch part : parts) close(s, part);
        for (int i = parts.size() - 1; i >= 0; i--) s.queue.addFirst(parts.get(i));
    }

    private static <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ---------- outbox ----------

    private void toOutbox(DocState s, WriteRequest request, List<Runnable> after) {
        String key = request.idempotencyKey();
        try {
            String docKey = Outbox.docKey(s.ref);
            outbox.append(docKey, request);
            s.outboxKeys.add(key);
            byOutboxKey.put(key, s);
            if (!appendListeners.isEmpty()) {
                after.add(() -> {
                    for (Consumer<String> l : appendListeners) {
                        try {
                            l.accept(docKey);
                        } catch (RuntimeException e) {
                            log.log(Level.WARNING, "Outbox append listener failed for " + docKey, e);
                        }
                    }
                });
            }
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Could not write to the outbox, the write to " + Outbox.docKey(s.ref)
                    + " is lost: " + request.ops(), e);
            dispatch(s.ref, s.handle.rejected(key), after);
        }
    }

    private void removeFromOutbox(DocState s, String key) {
        s.outboxKeys.remove(key);
        byOutboxKey.remove(key);
        try {
            outbox.remove(key);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Could not remove " + key + " from the outbox", e);
        }
    }

    private void moveRestToOutbox() {
        List<Runnable> after = new ArrayList<>();
        synchronized (lock) {
            flushed = true;
            for (DocState s : List.copyOf(docs.values())) {
                if (s.inFlight instanceof Batch b && !s.inFlightInOutbox) {
                    s.inFlightInOutbox = true;
                    toOutbox(s, b.request, after);
                }
                pump(s, after);
            }
        }
        run(after);
    }

    // ---------- state ----------

    private DocState state(DocRef ref) {
        return docs.computeIfAbsent(ref, DocState::new);
    }

    private void dropIfUnused(DocState s) {
        if (s.refs == 0 && s.idle() && s.outboxKeys.isEmpty()) docs.remove(s.ref, s);
    }

    private boolean allIdle() {
        for (DocState s : docs.values()) {
            if (!s.idle()) return false;
        }
        return true;
    }

    private void ack(DocState s, String key, List<Row> rows, List<Runnable> after) {
        List<Row> own = rowsByDoc(rows).getOrDefault(s.ref, List.of());
        dispatch(s.ref, s.handle.acked(key, own), after);
    }

    private void dispatch(DocRef ref, List<RowDiff> diffs, List<Runnable> after) {
        if (diffs.isEmpty() || listeners.isEmpty()) return;
        after.add(() -> callbackExecutor.execute(() -> {
            for (BiConsumer<DocRef, List<RowDiff>> l : listeners) {
                try {
                    l.accept(ref, diffs);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "Diff listener failed for " + Outbox.docKey(ref), e);
                }
            }
        }));
    }

    private static Map<DocRef, List<Row>> rowsByDoc(List<Row> rows) {
        Map<DocRef, List<Row>> out = new LinkedHashMap<>();
        for (Row row : rows) {
            DocRef doc = switch (row.ref()) {
                case PlayerRow p -> new PlayerDoc(p.player());
                case StoreDoc d -> d;
            };
            out.computeIfAbsent(doc, k -> new ArrayList<>()).add(row);
        }
        return out;
    }

    private String newKey() {
        return UuidV7.next(clock).toString();
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static void run(List<Runnable> actions) {
        for (Runnable r : actions) r.run();
    }

    private abstract static sealed class Item permits Batch, Conditional, Transfer, Direct {
        boolean waiting; // a conditional head waiting for the outbox to drain
    }

    /** Unconditional ops; {@code request} is set once the batch is closed. */
    private static final class Batch extends Item {
        final List<FieldOp> ops = new ArrayList<>(); // as sent, adjacent Inc coalesced
        final List<FieldOp> raw = new ArrayList<>(); // as called, matching the handle's pending ops
        final List<List<FieldOp>> calls = new ArrayList<>(); // raw, grouped by write call
        private long lastCall = -1;
        @Nullable WriteRequest request;

        /** Merges an {@code Inc} into an adjacent {@code Inc} on the same field; false if that is not possible. */
        boolean coalesce(FieldOp op) {
            if (op instanceof FieldOp.Inc inc && !ops.isEmpty() && ops.getLast() instanceof FieldOp.Inc last
                    && last.field().equals(inc.field())) {
                try {
                    ops.set(ops.size() - 1, new FieldOp.Inc(inc.field(), Math.addExact(last.delta(), inc.delta())));
                    return true;
                } catch (ArithmeticException overflow) {
                    return false; // keep both ops
                }
            }
            return false;
        }

        void addRaw(FieldOp op, long call) {
            raw.add(op);
            if (call != lastCall || calls.isEmpty()) {
                calls.add(new ArrayList<>());
                lastCall = call;
            }
            calls.getLast().add(op);
        }

        void addRaw(List<FieldOp> ops, long call) {
            for (FieldOp op : ops) addRaw(op, call);
        }
    }

    private static final class Conditional extends Item {
        final WriteRequest request;
        final CompletableFuture<ApplyResult> result;

        Conditional(WriteRequest request, CompletableFuture<ApplyResult> result) {
            this.request = request;
            this.result = result;
        }
    }

    private static final class Direct<T> extends Item {
        final String key;
        final java.util.function.Function<String, CompletableFuture<T>> call;
        final CompletableFuture<T> result;

        Direct(String key, java.util.function.Function<String, CompletableFuture<T>> call, CompletableFuture<T> result) {
            this.key = key;
            this.call = call;
            this.result = result;
        }
    }

    private static final class Transfer extends Item {
        final String key;
        final DocRef to;
        final FieldSpec field;
        final long amount;
        final CompletableFuture<TransferResult> result;

        Transfer(String key, DocRef to, FieldSpec field, long amount, CompletableFuture<TransferResult> result) {
            this.key = key;
            this.to = to;
            this.field = field;
            this.amount = amount;
            this.result = result;
        }
    }

    private static final class DocState {
        final DocRef ref;
        final DocHandle handle;
        final Deque<Item> queue = new ArrayDeque<>();
        final Set<String> outboxKeys = new HashSet<>();
        @Nullable Item inFlight;
        boolean inFlightInOutbox; // flush wrote the in-flight batch to the outbox already
        int refs;
        long calls; // numbers the write calls, so a rejected batch can be split per call

        DocState(DocRef ref) {
            this.ref = ref;
            this.handle = new DocHandle(ref);
        }

        boolean idle() {
            return inFlight == null && queue.isEmpty();
        }
    }
}
