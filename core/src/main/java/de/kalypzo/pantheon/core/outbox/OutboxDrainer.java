package de.kalypzo.pantheon.core.outbox;

import de.kalypzo.pantheon.core.backend.DataBackend;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.data.DuplicateValueException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Replays the outbox (agora.md §8 Nachholen, E13): each document in {@code seq} order, at most
 * {@value #PARALLEL_DOCS} documents at once. Before every send, and once a minute, entries older than
 * {@link #MAX_AGE} go to {@code outbox_dead} and count as rejected locally.
 *
 * <p>Per entry: applied → {@link Outbox#remove} <b>then</b> {@link DocWriter#outboxAcked}; rejected (unique violation
 * E27, invalid request, {@code applied == false}) → removed, ERROR logged, {@link DocWriter#outboxRejected}; anything
 * else (typically {@code DataUnavailableException}) → the same entry again after a backoff from {@link #MIN_BACKOFF}
 * doubling to {@link #MAX_BACKOFF}. A rejected entry is dropped as a whole: the outbox does not know the write calls
 * it was batched from. Thread-safe.
 */
public final class OutboxDrainer implements AutoCloseable {

    public static final Duration MAX_AGE = Duration.ofHours(20);
    public static final Duration EXPIRE_EVERY = Duration.ofMinutes(1);
    public static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    public static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    public static final int PARALLEL_DOCS = 8;

    private final Outbox outbox;
    private final DataBackend backend;
    private final DocWriter writer;
    private final ScheduledExecutorService scheduler;
    private final Logger log;
    private final de.kalypzo.pantheon.data.Subscription appendSubscription;

    private final Object lock = new Object();
    private final Map<String, Lane> lanes = new LinkedHashMap<>(); // guarded by lock; active documents
    private final Set<String> queued = new LinkedHashSet<>();       // guarded by lock; waiting for a lane
    private @Nullable CompletableFuture<Void> run;                  // guarded by lock; the current drain
    private @Nullable ScheduledFuture<?> expiry;                    // guarded by lock
    private boolean closed;                                         // guarded by lock

    public OutboxDrainer(Outbox outbox, DataBackend backend, DocWriter writer, Clock clock,
                         ScheduledExecutorService scheduler) {
        this(outbox, backend, writer, clock, scheduler, Logger.getLogger(OutboxDrainer.class.getName()));
    }

    public OutboxDrainer(Outbox outbox, DataBackend backend, DocWriter writer, Clock clock,
                         ScheduledExecutorService scheduler, Logger log) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.writer = Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(clock, "clock"); // ages are decided by the outbox's clock only (one source)
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.log = Objects.requireNonNull(log, "log");
        this.appendSubscription = writer.onOutboxAppend(this::wake);
    }

    /** Runs the minute task: expiry plus a sweep that starts lanes for documents nobody drains; under lock. */
    private void ensureStarted() {
        if (expiry == null) {
            expiry = scheduler.scheduleAtFixedRate(this::minuteTask, EXPIRE_EVERY.toMillis(),
                    EXPIRE_EVERY.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * The writer put a request of {@code docKey} into the outbox (E13: also while CONNECTED, e.g. after a deadline).
     * Starts a lane for the document unless one is running or queued; the lane runs on the scheduler, never on the
     * writing thread. A lane in backoff keeps its backoff.
     */
    private void wake(String docKey) {
        List<Lane> start;
        synchronized (lock) {
            if (closed || lanes.containsKey(docKey) || queued.contains(docKey)) return;
            ensureStarted();
            if (run == null) run = new CompletableFuture<>();
            queued.add(docKey);
            start = fill();
        }
        startLater(start);
    }

    private void minuteTask() {
        expire();
        List<Lane> start;
        synchronized (lock) {
            if (closed) return;
            Set<String> docs = outbox.pendingByDoc().keySet();
            for (String doc : docs) {
                if (!lanes.containsKey(doc)) queued.add(doc);
            }
            if (queued.isEmpty()) return;
            if (run == null) run = new CompletableFuture<>();
            start = fill();
        }
        startLater(start);
    }

    private void startLater(List<Lane> lanes) {
        for (Lane lane : lanes) {
            try {
                scheduler.execute(lane::run);
            } catch (RuntimeException e) { // scheduler shut down
                log.log(Level.WARNING, "Could not start the outbox replay of " + lane.doc, e);
            }
        }
    }

    /**
     * Replays everything in the outbox; call at start and on every {@code CONNECTED}. Joins a drain already running:
     * documents waiting in backoff are retried at once, new documents are added. Completes when every document of the
     * drain is empty; after {@link #close} it is cancelled.
     */
    public CompletableFuture<Void> drain() {
        List<Lane> start = new ArrayList<>();
        CompletableFuture<Void> result;
        synchronized (lock) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("drainer is closed"));
            ensureStarted();
            if (run == null) run = new CompletableFuture<>();
            result = run;
            for (String doc : outbox.pendingByDoc().keySet()) {
                if (!lanes.containsKey(doc)) queued.add(doc);
            }
            for (Lane lane : lanes.values()) {
                ScheduledFuture<?> retry = lane.retry;
                if (retry != null && retry.cancel(false)) { // reconnected: no need to wait for the backoff
                    lane.retry = null;
                    lane.backoff = MIN_BACKOFF;
                    start.add(lane);
                }
            }
            start.addAll(fill());
            finishIfIdle();
        }
        start.forEach(Lane::run);
        return result;
    }

    /** Stops the minute task and every retry; a running drain is cancelled. Entries stay in the outbox. */
    @Override
    public void close() {
        CompletableFuture<Void> current;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            appendSubscription.unsubscribe();
            if (expiry != null) expiry.cancel(false);
            for (Lane lane : lanes.values()) {
                if (lane.retry != null) lane.retry.cancel(false);
            }
            lanes.clear();
            queued.clear();
            current = run;
            run = null;
        }
        if (current != null) current.cancel(false);
    }

    // ---------- lanes ----------

    /** Starts lanes for queued documents up to {@value #PARALLEL_DOCS}; under lock. */
    private List<Lane> fill() {
        List<Lane> started = new ArrayList<>();
        var it = queued.iterator();
        while (lanes.size() < PARALLEL_DOCS && it.hasNext()) {
            String doc = it.next();
            it.remove();
            Lane lane = new Lane(doc);
            lanes.put(doc, lane);
            started.add(lane);
        }
        return started;
    }

    /** Under lock. */
    private void finishIfIdle() {
        if (lanes.isEmpty() && queued.isEmpty() && run != null) {
            CompletableFuture<Void> done = run;
            run = null;
            done.complete(null);
        }
    }

    private void laneDone(Lane lane) {
        List<Lane> start;
        synchronized (lock) {
            if (lanes.get(lane.doc) != lane) return;
            lanes.remove(lane.doc);
            // an append between the lane's last look and here found the lane still active and did not wake
            if (!closed && outbox.hasEntries(lane.doc)) queued.add(lane.doc);
            start = fill();
            finishIfIdle();
        }
        startLater(start); // not on this stack: thousands of documents would nest lane after lane
    }

    private boolean active(Lane lane) {
        synchronized (lock) {
            return !closed && lanes.get(lane.doc) == lane;
        }
    }

    private void expire() {
        try {
            outbox.moveExpired(MAX_AGE, dead -> writer.outboxRejected(dead.idempotencyKey()));
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Could not move expired outbox entries", e);
        }
    }

    /** One document: its entries one after another. Only one thread runs a lane at a time. */
    private final class Lane {
        final String doc;
        final Deque<OutboxEntry> todo = new ArrayDeque<>();
        long lastSeq = Long.MIN_VALUE;
        Duration backoff = MIN_BACKOFF;          // guarded by lock
        @Nullable ScheduledFuture<?> retry;      // guarded by lock

        Lane(String doc) {
            this.doc = doc;
        }

        /** Sends until the document is empty or a send is pending; trampolined for synchronous backends. */
        void run() {
            while (true) {
                if (!active(this)) return;
                OutboxEntry entry = next();
                if (entry == null) {
                    laneDone(this);
                    return;
                }
                expire(); // O(expired): the outbox keeps an index by age
                if (!outbox.contains(entry.idempotencyKey())) { // moved to outbox_dead, or removed by the writer
                    todo.pollFirst();
                    lastSeq = Math.max(lastSeq, entry.seq());
                    continue;
                }
                CompletableFuture<ApplyResult> sent;
                try {
                    sent = backend.apply(entry.request());
                } catch (RuntimeException e) {
                    sent = CompletableFuture.failedFuture(e);
                }
                if (!sent.isDone()) {
                    sent.whenComplete((r, e) -> {
                        if (handle(entry, r, e)) run();
                    });
                    return;
                }
                ApplyResult r = null;
                Throwable failure = null;
                try {
                    r = sent.join();
                } catch (RuntimeException e) {
                    failure = e;
                }
                if (!handle(entry, r, failure)) return;
            }
        }

        private @Nullable OutboxEntry next() {
            if (todo.isEmpty()) { // entries appended behind the ones already done
                for (OutboxEntry e : outbox.pending(doc)) {
                    if (e.seq() > lastSeq) todo.addLast(e);
                }
            }
            return todo.peekFirst();
        }

        /** True to go on with the next entry, false if a retry is scheduled or the lane stopped. */
        private boolean handle(OutboxEntry entry, @Nullable ApplyResult r, @Nullable Throwable e) {
            Throwable cause = e == null ? null : unwrap(e);
            String key = entry.idempotencyKey();
            if (e == null && r != null && r.applied()) {
                done(entry);
                removeQuietly(key);
                writer.outboxAcked(key, r); // after remove: the writer checks hasEntries to release waiting requests
                return true;
            }
            if (e == null || cause instanceof DuplicateValueException || cause instanceof IllegalArgumentException) {
                done(entry);
                removeQuietly(key);
                String reason = switch (cause) {
                    case null -> "was not applied by the backend";
                    case DuplicateValueException dup -> "violates unique index " + dup.index() + " (E27)";
                    default -> "was rejected as invalid";
                };
                log.log(Level.SEVERE, "Outbox replay of " + doc + " " + reason + " and is dropped: "
                        + SqliteOutbox.json(entry), cause);
                writer.outboxRejected(key);
                return true;
            }
            synchronized (lock) {
                if (closed || lanes.get(doc) != this) return false;
                Duration delay = backoff;
                backoff = delay.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay.multipliedBy(2);
                log.log(Level.FINE, "Outbox replay of " + doc + " failed, retrying in " + delay.toMillis() + " ms",
                        cause);
                retry = scheduler.schedule(this::retry, delay.toMillis(), TimeUnit.MILLISECONDS);
            }
            return false;
        }

        private void retry() {
            synchronized (lock) {
                if (retry == null) return; // drain() took over
                retry = null;
            }
            run();
        }

        private void done(OutboxEntry entry) {
            todo.pollFirst();
            lastSeq = Math.max(lastSeq, entry.seq());
            synchronized (lock) {
                backoff = MIN_BACKOFF;
            }
        }

        private void removeQuietly(String key) {
            try {
                outbox.remove(key);
            } catch (RuntimeException ex) {
                log.log(Level.WARNING, "Could not remove " + key + " from the outbox", ex);
            }
        }
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
