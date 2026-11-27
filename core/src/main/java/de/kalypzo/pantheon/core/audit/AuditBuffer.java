package de.kalypzo.pantheon.core.audit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The node's audit buffer (audit_log.md §7). {@link #add} never blocks. A bundle goes out once {@code batchSize}
 * entries are buffered or every {@code interval}; at most one bundle is in flight. A failed bundle goes back to the
 * front and is retried on the next tick. At {@code capacity} the oldest entry is dropped and counted; the count goes
 * out as {@code dropped} with the next bundle and is reset once that bundle succeeded.
 */
public final class AuditBuffer implements AutoCloseable {

    public static final int DEFAULT_BATCH_SIZE = 500;
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);
    public static final int DEFAULT_CAPACITY = 10_000;
    public static final Duration DEFAULT_CLOSE_MAX = Duration.ofSeconds(2);
    private static final Duration WARN_EVERY = Duration.ofMinutes(1);

    private final AuditBackend backend;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final int batchSize;
    private final int capacity;
    private final Logger log;
    private final ScheduledFuture<?> tick;

    private final Object lock = new Object();
    // all guarded by lock
    private final ArrayDeque<AuditRecord> queue = new ArrayDeque<>();
    private long dropped;
    private @Nullable Instant lastWarn;
    /** Completes after the in-flight bundle's outcome has been applied; null when nothing is in flight. */
    private @Nullable CompletableFuture<Boolean> inFlight;
    private boolean kickPending;
    private boolean waitForTick; // after a failure: no batch-size sends until the next tick
    private boolean closed;

    /** With the defaults 500, 5 s and 10,000. */
    public AuditBuffer(AuditBackend backend, ScheduledExecutorService scheduler, Clock clock, Logger log) {
        this(backend, scheduler, clock, DEFAULT_BATCH_SIZE, DEFAULT_INTERVAL, DEFAULT_CAPACITY, log);
    }

    public AuditBuffer(AuditBackend backend, ScheduledExecutorService scheduler, Clock clock,
                       int batchSize, Duration interval, int capacity, Logger log) {
        if (batchSize < 1 || capacity < 1 || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("batchSize, capacity und interval müssen positiv sein");
        }
        this.backend = Objects.requireNonNull(backend, "backend");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchSize = batchSize;
        this.capacity = capacity;
        this.log = Objects.requireNonNull(log, "log");
        long nanos = interval.toNanos();
        this.tick = scheduler.scheduleAtFixedRate(this::onTick, nanos, nanos, TimeUnit.NANOSECONDS);
    }

    /** Buffers the record. Never blocks, callable from any thread. After {@link #close} the record is discarded. */
    public void add(AuditRecord record) {
        Objects.requireNonNull(record, "record");
        String warning = null;
        boolean kick = false;
        synchronized (lock) {
            if (closed) return;
            if (queue.size() >= capacity) {
                queue.pollFirst();
                dropped++;
                warning = warningIfDue();
            }
            queue.addLast(record);
            if (queue.size() >= batchSize && inFlight == null && !kickPending && !waitForTick) {
                kickPending = true;
                kick = true;
            }
        }
        if (warning != null) log.warning(warning);
        if (kick) kick();
    }

    /** Entries dropped at capacity that no successful bundle has reported yet. */
    public long droppedSinceLastBundle() {
        synchronized (lock) {
            return dropped;
        }
    }

    /**
     * Last attempt to send what is buffered, for at most {@code max}, then stops. Callable from any thread. What is still
     * buffered afterwards is lost.
     */
    public void close(Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        synchronized (lock) {
            closed = true;
        }
        tick.cancel(false);
        while (true) {
            CompletableFuture<Boolean> wait;
            boolean ownSend = false;
            synchronized (lock) {
                if (inFlight != null) {
                    wait = inFlight;
                } else if (queue.isEmpty() && dropped == 0) {
                    return;
                } else {
                    wait = null;
                    ownSend = true;
                }
            }
            if (ownSend) {
                wait = send(true);
                if (wait == null) return;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            try {
                boolean ok = wait.get(remaining, TimeUnit.NANOSECONDS);
                if (!ok && ownSend) return; // our last attempt failed
            } catch (TimeoutException e) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                return; // not reached: the outcome stage never fails
            }
        }
    }

    @Override
    public void close() {
        close(DEFAULT_CLOSE_MAX);
    }

    private void onTick() {
        try {
            synchronized (lock) {
                waitForTick = false;
            }
            send(false);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Audit-Takt fehlgeschlagen", e); // keep the periodic task alive
        }
    }

    private void kick() {
        try {
            scheduler.execute(() -> {
                synchronized (lock) {
                    kickPending = false;
                }
                send(false);
            });
        } catch (RejectedExecutionException e) {
            synchronized (lock) {
                kickPending = false;
            }
        }
    }

    /**
     * Sends the next bundle (at most {@code batchSize} entries from the front) unless one is in flight or there is
     * nothing to send. Returns the stage that completes with the outcome after it has been applied, or null.
     */
    private @Nullable CompletableFuture<Boolean> send(boolean closing) {
        List<AuditRecord> entries;
        long reported;
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        synchronized (lock) {
            if (inFlight != null || (closed && !closing) || (queue.isEmpty() && dropped == 0)) return null;
            int n = Math.min(batchSize, queue.size());
            entries = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                entries.add(queue.pollFirst());
            }
            reported = dropped;
            inFlight = outcome;
        }
        CompletableFuture<Void> call;
        try {
            call = backend.append(new AuditBundle(entries, reported));
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        call.whenComplete((ok, error) -> finish(entries, reported, error, outcome));
        return outcome;
    }

    private void finish(List<AuditRecord> entries, long reported, @Nullable Throwable error,
                        CompletableFuture<Boolean> outcome) {
        String warning = null;
        boolean next = false;
        synchronized (lock) {
            inFlight = null;
            if (error == null) {
                dropped -= reported; // drops during the flight stay counted for the next bundle
                next = !closed && queue.size() >= batchSize && !kickPending;
                if (next) kickPending = true;
            } else {
                for (int i = entries.size() - 1; i >= 0; i--) {
                    queue.addFirst(entries.get(i));
                }
                long over = 0;
                while (queue.size() > capacity) {
                    queue.pollFirst();
                    over++;
                }
                if (over > 0) {
                    dropped += over;
                    warning = warningIfDue();
                }
                waitForTick = true;
            }
        }
        if (error != null) log.log(Level.FINE, "Audit-Bündel nicht gesendet, nächster Versuch beim nächsten Takt", error);
        if (warning != null) log.warning(warning);
        if (next) kick();
        outcome.complete(error == null);
    }

    /** Under lock: the WARN text if the last one is at least a minute ago (by the clock), else null. */
    private @Nullable String warningIfDue() {
        Instant now = clock.instant();
        if (lastWarn != null && Duration.between(lastWarn, now).compareTo(WARN_EVERY) < 0) return null;
        lastWarn = now;
        return "Audit-Puffer voll: " + dropped + " Einträge verworfen";
    }
}
