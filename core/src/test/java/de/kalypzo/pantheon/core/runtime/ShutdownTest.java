package de.kalypzo.pantheon.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.core.audit.AuditBuffer;
import de.kalypzo.pantheon.core.audit.AuditBundle;
import de.kalypzo.pantheon.core.audit.AuditRecord;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.core.outbox.Outbox;
import de.kalypzo.pantheon.core.outbox.OutboxDrainer;
import de.kalypzo.pantheon.core.data.DocHandle;
import de.kalypzo.pantheon.core.data.UuidV7;
import de.kalypzo.pantheon.spi.PantheonProvider;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bounded shutdown (P-26, R14): flush at most max, then backend, outbox, unregister. */
class ShutdownTest {

    private static final FieldSpec COINS = new FieldSpec("economy", "coins", ScalarType.LONG, 100L);
    private static final DocRef ALICE = new PlayerDoc(UUID.fromString("00000000-0000-0000-0000-00000000000a"));

    @TempDir
    Path dir;

    private final Logger log = Logger.getLogger("ShutdownTest");
    private final List<String> order = new CopyOnWriteArrayList<>();

    /** A backend whose writes never get an answer; everything else is the real node. */
    private static Backend hanging(Backend real, List<String> order, List<WriteRequest> sent) {
        return (Backend) Proxy.newProxyInstance(Backend.class.getClassLoader(), new Class<?>[] {Backend.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("apply")) {
                        sent.add((WriteRequest) args[0]);
                        return new CompletableFuture<ApplyResult>();
                    }
                    if (method.getName().equals("close")) order.add("backend");
                    return method.invoke(real, args);
                });
    }

    private Outbox recordingOutbox(Path file) {
        Outbox real = Outbox.open(file, Clock.systemUTC(), log);
        return (Outbox) Proxy.newProxyInstance(Outbox.class.getClassLoader(), new Class<?>[] {Outbox.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) order.add("outbox");
                    return method.invoke(real, args);
                });
    }

    private static FieldOp inc(long by) {
        return new FieldOp.Inc(COINS, by);
    }

    @Test
    void flushWaitsUpToMaxThenOutbox() throws Exception {
        PantheonProvider.register(p -> null);
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            List<WriteRequest> sent = new CopyOnWriteArrayList<>();
            Backend backend = hanging(network.node("a"), order, sent);
            Outbox outbox = recordingOutbox(dir.resolve("outbox.db"));
            DocWriter writer = new DocWriter(backend, outbox, Clock.systemUTC(), Runnable::run, log);
            writer.write(ALICE, List.of(inc(1)));   // hangs
            writer.write(ALICE, List.of(inc(2)));   // behind it

            long start = System.nanoTime();
            Shutdown.run(writer, outbox, backend, Duration.ofMillis(300));
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(290)).isLessThan(Duration.ofSeconds(5));
            assertThat(order).containsExactly("backend", "outbox"); // backend first, outbox last
            assertThat(sent).hasSize(1);
            assertThat(PantheonProvider.class).isNotNull();
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> PantheonProvider.get(null)); // unregistered
            // both ops are in the outbox file now
            try (Outbox reopened = Outbox.open(dir.resolve("outbox.db"), Clock.systemUTC(), log)) {
                assertThat(reopened.pendingByDoc().values().stream().mapToInt(List::size).sum()).isEqualTo(2);
            }
        }
    }

    @Test
    void outboxClosesOnlyAfterALateMoveToTheOutbox() throws Exception {
        // deferred P-26: the flush future misses max + 1 s (slow outbox I/O); the outbox must not close under the move
        PantheonProvider.register(p -> null);
        List<java.util.logging.LogRecord> severe = new CopyOnWriteArrayList<>();
        Logger writerLog = Logger.getLogger("ShutdownTest.late." + UUID.randomUUID());
        writerLog.setUseParentHandlers(false);
        writerLog.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                if (r.getLevel() == java.util.logging.Level.SEVERE) severe.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            List<WriteRequest> sent = new CopyOnWriteArrayList<>();
            Backend backend = hanging(network.node("a"), order, sent);
            Outbox real = Outbox.open(dir.resolve("outbox.db"), Clock.systemUTC(), log);
            Outbox slow = (Outbox) Proxy.newProxyInstance(Outbox.class.getClassLoader(), new Class<?>[] {Outbox.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("append")) Thread.sleep(800);
                        try {
                            return method.invoke(real, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            DocWriter writer = new DocWriter(backend, slow, Clock.systemUTC(), Runnable::run, writerLog);
            DocRef bob = new PlayerDoc(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
            writer.write(ALICE, List.of(inc(1)));   // in flight, hangs
            writer.write(bob, List.of(inc(2)));     // in flight, hangs

            Shutdown.run(writer, slow, backend, Duration.ofMillis(100)); // 2 x 800 ms of moving > 100 ms + 1 s

            assertThat(severe).extracting(java.util.logging.LogRecord::getMessage).isEmpty();
            try (Outbox reopened = Outbox.open(dir.resolve("outbox.db"), Clock.systemUTC(), log)) {
                assertThat(reopened.pendingByDoc().values().stream().mapToInt(List::size).sum()).isEqualTo(2);
            }
        }
    }

    @Test
    void idleWriterReturnsAtOnce() {
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            Backend backend = network.node("a");
            Outbox outbox = recordingOutbox(dir.resolve("outbox.db"));
            DocWriter writer = new DocWriter(backend, outbox, Clock.systemUTC(), Runnable::run, log);
            long start = System.nanoTime();
            Shutdown.run(writer, outbox, backend, Duration.ofSeconds(10));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        }
    }

    @Test
    void outboxEntriesFromShutdownAreDrainedOnNextStart() throws Exception {
        Path file = dir.resolve("outbox.db");
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            Backend first = hanging(network.node("a"), order, new CopyOnWriteArrayList<>());
            Outbox outbox = Outbox.open(file, Clock.systemUTC(), log);
            DocWriter writer = new DocWriter(first, outbox, Clock.systemUTC(), Runnable::run, log);
            writer.write(ALICE, List.of(inc(5)));
            writer.write(ALICE, List.of(inc(7)));
            Shutdown.run(writer, outbox, first, Duration.ofMillis(100));

            // next start: same file, same network, real backend
            Backend second = network.node("a");
            Outbox reopened = Outbox.open(file, Clock.systemUTC(), log);
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            try {
                DocWriter writer2 = new DocWriter(second, reopened, Clock.systemUTC(), Runnable::run, log);
                OutboxDrainer drainer = new OutboxDrainer(reopened, second, writer2, Clock.systemUTC(), scheduler, log);
                drainer.drain().get(5, TimeUnit.SECONDS);

                await().atMost(Duration.ofSeconds(5)).until(() -> reopened.pendingByDoc().isEmpty());
                DocHandle handle = writer2.acquire(ALICE);
                handle.replaceAll(second.load(ALICE).get(5, TimeUnit.SECONDS));
                assertThat(handle.view("economy")).containsEntry("coins", 112L); // 100 default + 5 + 7
                drainer.close();
            } finally {
                reopened.close();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void parallelStepRunsInsideTheSameBudget() {
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            Backend backend = hanging(network.node("a"), order, new CopyOnWriteArrayList<>()); // records close
            Outbox outbox = recordingOutbox(dir.resolve("outbox.db"));
            DocWriter writer = new DocWriter(backend, outbox, Clock.systemUTC(), Runnable::run, log);
            List<Long> startedAt = new ArrayList<>();
            long t0 = System.nanoTime();
            Shutdown.run(writer, outbox, backend, Duration.ofSeconds(10), log, List.of(
                    new Shutdown.Step("audit", () -> {
                        synchronized (startedAt) { startedAt.add(System.nanoTime() - t0); }
                        Thread.sleep(300);
                        order.add("audit");
                    })), List.of());
            assertThat(order).containsExactly("audit", "backend", "outbox"); // parallel step ends before the backend closes
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(290));
        }
    }

    @Test
    void hangingStepDoesNotExceedTheBudget() {
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            Backend backend = hanging(network.node("a"), order, new CopyOnWriteArrayList<>()); // records close
            Outbox outbox = recordingOutbox(dir.resolve("outbox.db"));
            DocWriter writer = new DocWriter(backend, outbox, Clock.systemUTC(), Runnable::run, log);
            long t0 = System.nanoTime();
            Shutdown.run(writer, outbox, backend, Duration.ofMillis(300), log, List.of(
                    new Shutdown.Step("stuck", () -> Thread.sleep(60_000))), List.of());
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(5));
            assertThat(order).containsExactly("backend", "outbox");
        }
    }

    @Test
    void auditFlushedBeforeBackendClose() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // R14, deterministic: each side waits for the other; run one after the other, one latch times out. The
        // append side gives up within the buffer's own 2 s (close(2 s) would stop waiting and let it pass later).
        CountDownLatch appendCalled = new CountDownLatch(1);
        CountDownLatch otherStarted = new CountDownLatch(1);
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            Backend real = network.node("a");
            List<AuditBundle> appended = new CopyOnWriteArrayList<>();
            Backend backend = (Backend) Proxy.newProxyInstance(Backend.class.getClassLoader(),
                    new Class<?>[] {Backend.class}, (proxy, method, args) -> {
                        if (method.getName().equals("append")) { // answers once the other step has started
                            appended.add((AuditBundle) args[0]);
                            appendCalled.countDown();
                            CompletableFuture<?> stored = (CompletableFuture<?>) method.invoke(real, args);
                            return stored.thenApplyAsync(v -> {
                                order.add(awaitLatch(otherStarted, 1500) ? "append" : "append-timeout");
                                return v;
                            }, r -> new Thread(r, "append-answer").start());
                        }
                        if (method.getName().equals("close")) order.add("backend");
                        return method.invoke(real, args);
                    });
            Outbox outbox = recordingOutbox(dir.resolve("outbox.db"));
            DocWriter writer = new DocWriter(backend, outbox, Clock.systemUTC(), Runnable::run, log);
            AuditBuffer audit = new AuditBuffer(backend, scheduler, Clock.systemUTC(), log);
            UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000000a");
            audit.add(new AuditRecord(UuidV7.next(Clock.systemUTC()), alice, "shop:open", null, null, null, Map.of()));

            long t0 = System.nanoTime();
            Shutdown.run(writer, outbox, backend, Duration.ofSeconds(10), log, audit, List.of(
                    new Shutdown.Step("other", () -> {
                        otherStarted.countDown();
                        order.add(awaitLatch(appendCalled, 5000) ? "other" : "other-timeout");
                    })), List.of());
            Duration took = Duration.ofNanos(System.nanoTime() - t0);

            assertThat(appended).singleElement().satisfies(b -> assertThat(b.entries()).hasSize(1));
            assertThat(order).noneMatch(e -> e.endsWith("-timeout"))
                    .containsSubsequence("append", "backend", "outbox")
                    .containsSubsequence("other", "backend", "outbox");
            assertThat(took).isLessThan(Duration.ofSeconds(5)); // sanity only
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static boolean awaitLatch(CountDownLatch latch, long millis) {
        try {
            return latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
