package de.kalypzo.pantheon.core.runtime;

import de.kalypzo.pantheon.core.audit.AuditBuffer;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.outbox.Outbox;
import de.kalypzo.pantheon.spi.PantheonProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The bounded shutdown of a node (P-26; player §9, agora §8): Pantheon waits at most {@code max} (10 s,
 * {@link Bootstrap#SHUTDOWN_FLUSH}), whatever is still pending stays in the outbox, and the server is never shut
 * down by Pantheon.
 *
 * <p>Order:
 * <ol>
 *   <li><b>Phase 1, in parallel, one shared deadline {@code max}:</b> {@link DocWriter#flush(Duration)} (the rest
 *       goes to the outbox) and every extra {@link Step} (R14: the audit plan adds {@code auditBuffer.close(2 s)} here
 *       as a step; it neither lengthens the budget nor changes the semantics). A step that is still running at the
 *       deadline is logged and abandoned.
 *   <li>{@code beforeBackend} closeables in the given order (sync, drainer, stores, players: they must not touch the
 *       outbox or backend any more), then the {@link Backend}, then {@link DocWriter#moveRestToOutboxNow()} (a flush that
 *       missed the deadline may still be moving to the outbox; this finishes or waits for the move under the writer
 *       lock), then the {@link Outbox} (its file I/O is done under the writer lock, so nothing queued is written to it
 *       after that), then {@link PantheonProvider#unregister()}.
 * </ol>
 * Failures of single parts are logged and never stop the rest.
 */
public final class Shutdown {

    /** One extra participant of phase 1. It should honour its own, shorter timeout; the shared deadline is a net. */
    public record Step(String name, Action action) {
        public Step {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(action, "action");
        }
    }

    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    private Shutdown() {}

    public static void run(DocWriter writer, Outbox outbox, Backend backend, Duration max) {
        run(writer, outbox, backend, max, Logger.getLogger(Shutdown.class.getName()), List.of(), List.of());
    }

    /**
     * R14: like {@link #run(DocWriter, Outbox, Backend, Duration, Logger, List, List)}, with the last send of the
     * node's {@link AuditBuffer} ({@code close(2 s)}, audit §7) as one more step of phase 1, in parallel to the flush
     * and inside the same budget; the backend closes only after it.
     */
    public static void run(DocWriter writer, Outbox outbox, Backend backend, Duration max, Logger log,
                           AuditBuffer audit, List<Step> parallel, List<? extends AutoCloseable> beforeBackend) {
        Objects.requireNonNull(audit, "audit");
        List<Step> steps = new ArrayList<>();
        steps.add(new Step("audit", () -> audit.close(AuditBuffer.DEFAULT_CLOSE_MAX)));
        steps.addAll(parallel);
        run(writer, outbox, backend, max, log, steps, beforeBackend);
    }

    public static void run(DocWriter writer, Outbox outbox, Backend backend, Duration max, Logger log,
                           List<Step> parallel, List<? extends AutoCloseable> beforeBackend) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(outbox, "outbox");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(log, "log");

        List<CompletableFuture<?>> phase = new ArrayList<>();
        List<String> names = new ArrayList<>();
        phase.add(writer.flush(max));
        names.add("flush");
        for (Step step : parallel) {
            names.add(step.name());
            CompletableFuture<Void> f = new CompletableFuture<>();
            Thread t = new Thread(() -> {
                try {
                    step.action().run();
                    f.complete(null);
                } catch (InterruptedException e) {
                    f.complete(null);
                } catch (Throwable e) {
                    log.log(Level.WARNING, "Shutdown step '" + step.name() + "' failed", e);
                    f.complete(null);
                }
            }, "pantheon-shutdown-" + step.name());
            t.setDaemon(true);
            t.start();
            phase.add(f);
        }
        long deadline = System.nanoTime() + max.toNanos() + TimeUnit.SECONDS.toNanos(1); // flush times out by itself
        for (int i = 0; i < phase.size(); i++) {
            try {
                phase.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.TimeoutException e) {
                log.warning("Shutdown step '" + names.get(i) + "' did not finish within " + max.toMillis() + " ms");
            } catch (Exception e) {
                log.log(Level.WARNING, "Shutdown step '" + names.get(i) + "' failed", e);
            }
        }

        Bootstrap.closeQuietly(log, beforeBackend.toArray(AutoCloseable[]::new));
        Bootstrap.closeQuietly(log, backend);
        // the flush may have missed the deadline while still moving to the outbox: finish (or wait for) the move
        // under the writer lock first, so the outbox never closes under it
        Bootstrap.closeQuietly(log, writer::moveRestToOutboxNow);
        Bootstrap.closeQuietly(log, outbox);
        PantheonProvider.unregister();
    }
}
