package de.kalypzo.pantheon.core.runtime;

import de.kalypzo.pantheon.core.audit.AuditBuffer;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.outbox.Outbox;
import de.kalypzo.pantheon.core.outbox.OutboxDrainer;
import de.kalypzo.pantheon.core.player.PlayerDataEngine;
import de.kalypzo.pantheon.core.player.PresenceCache;
import de.kalypzo.pantheon.core.store.StoreRegistry;
import de.kalypzo.pantheon.core.sync.ReconnectSync;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/** What {@link Bootstrap#start} produced. */
public sealed interface BootstrapResult {

    /**
     * The whole core graph of this node (R11), shared by every plugin. {@link #close()} stops it: pending writes get
     * at most {@link Bootstrap#SHUTDOWN_FLUSH}, the rest goes to the outbox ({@link Shutdown#run}).
     *
     * @param network the {@link InMemoryNetwork} in memory mode (R12), else empty
     * @param executors threads Pantheon owns besides {@code scheduler} (writer callbacks, own listener thread)
     * @param loginTimeout {@code PANTHEON_LOGIN_TIMEOUT_MS} (default 5000), for the login of P-24
     */
    record Started(
            String nodeId,
            Backend backend,
            Optional<InMemoryNetwork> network,
            Clock clock,
            Outbox outbox,
            DocWriter writer,
            OutboxDrainer drainer,
            PlayerDataEngine players,
            StoreRegistry stores,
            PresenceCache presence,
            ReconnectSync sync,
            Duration loginTimeout,
            ScheduledExecutorService scheduler,
            List<ExecutorService> executors,
            Logger log) implements BootstrapResult, AutoCloseable {

        public Started {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(outbox, "outbox");
            Objects.requireNonNull(writer, "writer");
            Objects.requireNonNull(drainer, "drainer");
            Objects.requireNonNull(players, "players");
            Objects.requireNonNull(stores, "stores");
            Objects.requireNonNull(presence, "presence");
            Objects.requireNonNull(sync, "sync");
            Objects.requireNonNull(loginTimeout, "loginTimeout");
            Objects.requireNonNull(scheduler, "scheduler");
            executors = List.copyOf(executors);
            Objects.requireNonNull(log, "log");
        }

        /** Flushes for at most {@link Bootstrap#SHUTDOWN_FLUSH} (the rest goes to the outbox), then closes every part (P-26). */
        @Override
        public void close() {
            shutdown(null);
        }

        /**
         * Like {@link #close()}, and the last send of the node's audit buffer runs in parallel to the flush, inside
         * the same budget, before the backend closes (R14, audit §7).
         */
        public void close(AuditBuffer audit) {
            shutdown(Objects.requireNonNull(audit, "audit"));
        }

        private void shutdown(@Nullable AuditBuffer audit) {
            List<AutoCloseable> beforeBackend = List.of(sync, drainer, stores, players);
            if (audit == null) {
                Shutdown.run(writer, outbox, backend, Bootstrap.SHUTDOWN_FLUSH, log, List.of(), beforeBackend);
            } else {
                Shutdown.run(writer, outbox, backend, Bootstrap.SHUTDOWN_FLUSH, log, audit, List.of(), beforeBackend);
            }
            scheduler.shutdownNow();
            executors.forEach(ExecutorService::shutdownNow);
        }
    }

    /** A missing or invalid variable, or Agora rejected the token (FATAL). The message never contains the token. */
    record ConfigError(String message) implements BootstrapResult {
        public ConfigError {
            Objects.requireNonNull(message, "message");
        }
    }
}
