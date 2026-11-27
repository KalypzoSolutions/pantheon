package de.kalypzo.pantheon.core.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** A single-threaded scheduler on virtual time: tasks run only in {@link #advance}, on the calling thread. */
public final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {

    private final MutableClock clock;
    private final List<Task> tasks = new ArrayList<>();
    private long nanos; // virtual time
    private long order;
    private boolean shutdown;

    public ManualScheduler(MutableClock clock) {
        this.clock = clock;
    }

    /** Delays (ms) of every {@code schedule} call so far, in order ({@code execute} is not recorded). */
    final List<Long> oneShotDelaysMillis = new ArrayList<>();

    /** Moves time forward, running every task that becomes due, in due order. */
    public void advance(Duration d) {
        long target = nanos + d.toNanos();
        while (true) {
            Task next;
            synchronized (this) {
                next = tasks.stream().filter(t -> !t.cancelled && t.due <= target)
                        .min(Comparator.<Task>comparingLong(t -> t.due).thenComparingLong(t -> t.order)).orElse(null);
                if (next == null) break;
                tasks.remove(next);
                clock.advance(Duration.ofNanos(next.due - nanos));
                nanos = next.due;
                if (next.period > 0) {
                    next.due += next.period;
                    tasks.add(next);
                } else {
                    next.done = true;
                }
            }
            next.command.run();
        }
        clock.advance(Duration.ofNanos(target - nanos));
        nanos = target;
    }

    /** Runs what is due now (delay 0) without moving time. */
    public void runDue() {
        advance(Duration.ZERO);
    }

    synchronized int pendingOneShots() {
        return (int) tasks.stream().filter(t -> !t.cancelled && t.period == 0).count();
    }

    @Override
    public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        oneShotDelaysMillis.add(unit.toMillis(delay));
        return enqueue(command, delay, unit);
    }

    private synchronized Task enqueue(Runnable command, long delay, TimeUnit unit) {
        Task t = new Task(command, nanos + unit.toNanos(delay), 0, order++);
        tasks.add(t);
        return t;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public synchronized ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                               TimeUnit unit) {
        Task t = new Task(command, nanos + unit.toNanos(initialDelay), unit.toNanos(period), order++);
        tasks.add(t);
        return t;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                     TimeUnit unit) {
        return scheduleAtFixedRate(command, initialDelay, delay, unit);
    }

    @Override
    public void execute(Runnable command) {
        enqueue(command, 0, TimeUnit.NANOSECONDS); // not a delay: not recorded
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }

    private final class Task implements ScheduledFuture<Object> {
        final Runnable command;
        long due;
        final long period;
        final long order;
        volatile boolean cancelled;
        volatile boolean done;

        Task(Runnable command, long due, long period, long order) {
            this.command = command;
            this.due = due;
            this.period = period;
            this.order = order;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            synchronized (ManualScheduler.this) {
                return unit.convert(due - nanos, TimeUnit.NANOSECONDS);
            }
        }

        @Override
        public int compareTo(Delayed o) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), o.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (done) return false;
            cancelled = true;
            synchronized (ManualScheduler.this) {
                tasks.remove(this);
            }
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
