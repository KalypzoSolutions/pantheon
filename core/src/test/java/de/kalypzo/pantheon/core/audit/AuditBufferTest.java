package de.kalypzo.pantheon.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.core.outbox.ManualScheduler;
import de.kalypzo.pantheon.core.outbox.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** P-32 (audit_log.md §7). */
class AuditBufferTest {

    private static final Duration INTERVAL = Duration.ofSeconds(5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final FakeBackend backend = new FakeBackend();
    private final List<LogRecord> logs = new CopyOnWriteArrayList<>();
    private final Logger log = Logger.getLogger("AuditBufferTest." + UUID.randomUUID());

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
    }

    private AuditBuffer buffer(int capacity) {
        return new AuditBuffer(backend, scheduler, clock, 500, INTERVAL, capacity, log);
    }

    private AuditBuffer buffer() {
        return buffer(10_000);
    }

    private static AuditRecord record(int n) {
        return new AuditRecord(new UUID(0, n), new UUID(1, 1), "test:n" + n, null, null, null, Map.of());
    }

    private static void addN(AuditBuffer b, int from, int count) {
        for (int i = from; i < from + count; i++) {
            b.add(record(i));
        }
    }

    private static List<Integer> numbers(AuditBundle bundle) {
        return bundle.entries().stream().map(r -> (int) r.id().getLeastSignificantBits()).toList();
    }

    private long warnings() {
        return logs.stream().filter(r -> r.getLevel() == Level.WARNING).count();
    }

    @Test
    void flushesAfterInterval() {
        AuditBuffer b = buffer();
        addN(b, 1, 3);
        scheduler.runDue();
        assertThat(backend.calls).isEmpty();

        scheduler.advance(INTERVAL);

        assertThat(backend.calls).hasSize(1);
        assertThat(numbers(backend.calls.getFirst().bundle)).containsExactly(1, 2, 3);
    }

    @Test
    void flushesAtBatchSizeWithoutWaiting() {
        AuditBuffer b = buffer();
        addN(b, 1, 500);

        scheduler.runDue();

        assertThat(backend.calls).hasSize(1);
        assertThat(backend.calls.getFirst().bundle.entries()).hasSize(500);
    }

    @Test
    void oneBundleInFlight() {
        AuditBuffer b = buffer();
        addN(b, 1, 500);
        scheduler.runDue();
        addN(b, 501, 500);
        scheduler.runDue();
        scheduler.advance(INTERVAL);
        scheduler.advance(INTERVAL);

        assertThat(backend.calls).hasSize(1);

        backend.calls.getFirst().future.complete(null);
        scheduler.runDue();

        assertThat(backend.calls).hasSize(2);
        assertThat(numbers(backend.calls.get(1).bundle)).first().isEqualTo(501);
    }

    @Test
    void drainsBacklogInFullBundles() {
        AuditBuffer b = buffer();
        addN(b, 1, 1_200);
        scheduler.runDue();
        assertThat(backend.calls).hasSize(1);
        assertThat(backend.calls.get(0).bundle.entries()).hasSize(500);

        backend.calls.get(0).future.complete(null);
        scheduler.runDue();
        assertThat(backend.calls).hasSize(2);
        assertThat(backend.calls.get(1).bundle.entries()).hasSize(500);

        backend.calls.get(1).future.complete(null);
        scheduler.runDue();
        assertThat(backend.calls).hasSize(2); // the 200 wait for the next tick

        scheduler.advance(INTERVAL);
        assertThat(backend.calls).hasSize(3);
        assertThat(numbers(backend.calls.get(2).bundle)).hasSize(200).first().isEqualTo(1_001);
    }

    @Test
    void failedBundleRetriedNextTick() {
        AuditBuffer b = buffer();
        addN(b, 1, 3);
        scheduler.advance(INTERVAL);
        assertThat(backend.calls).hasSize(1);

        backend.calls.getFirst().future.completeExceptionally(new RuntimeException("agora down"));
        addN(b, 4, 1);
        scheduler.runDue();
        assertThat(backend.calls).hasSize(1); // no retry before the next tick

        scheduler.advance(INTERVAL);

        assertThat(backend.calls).hasSize(2);
        assertThat(numbers(backend.calls.get(1).bundle)).containsExactly(1, 2, 3, 4);
    }

    @Test
    void capacityDropsOldestAndCounts() {
        AuditBuffer b = buffer(5);
        addN(b, 1, 7);

        assertThat(b.droppedSinceLastBundle()).isEqualTo(2);

        scheduler.advance(INTERVAL);
        AuditBundle bundle = backend.calls.getFirst().bundle;
        assertThat(numbers(bundle)).containsExactly(3, 4, 5, 6, 7);
        assertThat(bundle.dropped()).isEqualTo(2);
    }

    @Test
    void droppedReportedOnceThenReset() {
        AuditBuffer b = buffer(5);
        addN(b, 1, 7);
        scheduler.advance(INTERVAL);
        backend.calls.getFirst().future.complete(null);

        assertThat(b.droppedSinceLastBundle()).isZero();

        addN(b, 8, 1);
        scheduler.advance(INTERVAL);
        assertThat(backend.calls).hasSize(2);
        assertThat(backend.calls.get(1).bundle.dropped()).isZero();
    }

    @Test
    void droppedKeptWhenBundleFails() {
        AuditBuffer b = buffer(5);
        addN(b, 1, 7);
        scheduler.advance(INTERVAL);
        backend.calls.getFirst().future.completeExceptionally(new RuntimeException("agora down"));

        assertThat(b.droppedSinceLastBundle()).isEqualTo(2);
        scheduler.advance(INTERVAL);
        assertThat(backend.calls.get(1).bundle.dropped()).isEqualTo(2);
    }

    @Test
    void warnAtMostOncePerMinute() {
        AuditBuffer b = buffer(1);
        addN(b, 1, 3);
        assertThat(warnings()).isEqualTo(1);
        assertThat(logs.getFirst().getMessage()).isEqualTo("Audit-Puffer voll: 1 Einträge verworfen");

        clock.advance(Duration.ofSeconds(59));
        addN(b, 4, 5);
        assertThat(warnings()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        addN(b, 9, 1);
        assertThat(warnings()).isEqualTo(2);
        assertThat(logs.getLast().getMessage()).isEqualTo("Audit-Puffer voll: 8 Einträge verworfen");
    }

    @Test
    void closeSendsRemainingEntries() {
        AuditBuffer b = buffer();
        backend.autoComplete = true;
        addN(b, 1, 3);

        b.close(Duration.ofSeconds(2));

        assertThat(backend.calls).hasSize(1);
        assertThat(numbers(backend.calls.getFirst().bundle)).containsExactly(1, 2, 3);
    }

    @Test
    void closeTriesForAtMostMax() throws Exception {
        AuditBuffer b = buffer(); // backend never answers
        addN(b, 1, 3);

        Thread closer = Thread.ofPlatform().start(() -> b.close(Duration.ofMillis(200)));
        long start = System.nanoTime();
        closer.join(Duration.ofSeconds(5));
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(closer.isAlive()).isFalse();
        assertThat(tookMillis).isLessThanOrEqualTo(300);
        assertThat(backend.calls).hasSize(1);
    }

    @Test
    void addNeverBlocksWhileSendHangs() throws Exception {
        ScheduledExecutorService real = Executors.newSingleThreadScheduledExecutor();
        try {
            AuditBuffer b = new AuditBuffer(backend, real, clock, 500, INTERVAL, 10_000, log);
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                int base = t * 10_000;
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    addN(b, base, 10_000);
                }));
            }
            long begin = System.nanoTime();
            start.countDown();
            for (Thread t : threads) {
                t.join(Duration.ofSeconds(5));
            }
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

            assertThat(threads).noneMatch(Thread::isAlive);
            assertThat(tookMillis).isLessThan(1_000);
            assertThat(backend.calls).hasSizeLessThanOrEqualTo(1);
        } finally {
            real.shutdownNow();
        }
    }

    /** Records every call; its future completes only when the test says so (or at once with {@code autoComplete}). */
    private static final class FakeBackend implements AuditBackend {

        record Call(AuditBundle bundle, CompletableFuture<Void> future) {}

        final List<Call> calls = new CopyOnWriteArrayList<>();
        volatile boolean autoComplete;

        @Override
        public CompletableFuture<Void> append(AuditBundle bundle) {
            CompletableFuture<Void> f = autoComplete ? CompletableFuture.completedFuture(null) : new CompletableFuture<>();
            calls.add(new Call(bundle, f));
            return f;
        }

        @Override
        public CompletableFuture<TimelineResult> timeline(TimelineSpec spec) {
            throw new UnsupportedOperationException();
        }
    }
}
