package de.kalypzo.pantheon.core.runtime;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.BackendFactory;
import de.kalypzo.pantheon.core.backend.ConnectionState;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.outbox.Outbox;
import de.kalypzo.pantheon.core.outbox.OutboxDrainer;
import de.kalypzo.pantheon.core.player.PlayerDataEngine;
import de.kalypzo.pantheon.core.player.PresenceCache;
import de.kalypzo.pantheon.core.store.StoreRegistry;
import de.kalypzo.pantheon.core.sync.ReconnectSync;
import de.kalypzo.pantheon.data.Subscription;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the core graph of a node from the environment (R11, E12) and starts the backend with the
 * {@link ReconnectSync} as its {@code NodeState}:
 * <ol>
 *   <li>{@code PANTHEON_LOGIN_TIMEOUT_MS} (default 5000) and the backend ({@link BackendFactory}: {@code agora}, the
 *       default, needs the three Agora variables; {@code memory} none, R12).
 *   <li>{@code <dataFolder>/outbox.db}, {@link DocWriter} and {@link OutboxDrainer} on the same {@link Clock}, the
 *       {@link PlayerDataEngine}, {@link StoreRegistry} and {@link PresenceCache}; listeners run on
 *       {@code listenerExecutor} (E15: paper passes the main thread).
 *   <li>{@code backend.start(sync)}, then it waits at most {@code firstConnectWait} (5 s, E12) for the first outcome:
 *       {@code CONNECTED} (R49: proves the token, the sync drains the outbox and resyncs), {@code FATAL} (token
 *       rejected: {@link BootstrapResult.ConfigError}, everything closed again), or nothing yet (Agora unreachable:
 *       a WARNING, the node starts and connects later). In memory mode the backend is connected from the start, so
 *       {@link ReconnectSync#sync()} is called once.
 * </ol>
 * No message contains the token.
 */
public final class Bootstrap {

    /**
     * Raising this above about 8 s needs a matching {@code AGORA_TELEPORT_CONNECT_MS} in Agora: a network teleport
     * waits for the login on the target, otherwise it ends with {@code TIMEOUT} although the switch would still work.
     */
    public static final String LOGIN_TIMEOUT_MS = "PANTHEON_LOGIN_TIMEOUT_MS";
    public static final Duration DEFAULT_LOGIN_TIMEOUT = Duration.ofMillis(5000);
    /** E12: {@code onEnable} waits at most this long for the first connection outcome. */
    public static final Duration FIRST_CONNECT_WAIT = Duration.ofSeconds(5);
    /** Global value "Shutdown": at most 10 s, the rest goes to the outbox. */
    public static final Duration SHUTDOWN_FLUSH = Duration.ofSeconds(10);
    static final String OUTBOX_FILE = "outbox.db";

    private Bootstrap() {}

    /** Listeners on a thread of Pantheon's own; paper uses {@link #start(Map, Path, Duration, Executor, Logger)}. */
    public static BootstrapResult start(Map<String, String> env, Path dataFolder, Duration firstConnectWait) {
        ExecutorService listeners = daemonSingle("pantheon-listeners");
        BootstrapResult result = start(env, dataFolder, firstConnectWait, listeners,
                Logger.getLogger(Bootstrap.class.getName()), List.of(listeners)); // Started.close() stops it
        if (result instanceof BootstrapResult.ConfigError) listeners.shutdownNow();
        return result;
    }

    public static BootstrapResult start(Map<String, String> env, Path dataFolder, Duration firstConnectWait,
                                        Executor listenerExecutor, Logger log) {
        return start(env, dataFolder, firstConnectWait, listenerExecutor, log, List.of());
    }

    private static BootstrapResult start(Map<String, String> env, Path dataFolder, Duration firstConnectWait,
                                         Executor listenerExecutor, Logger log, List<ExecutorService> owned) {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(firstConnectWait, "firstConnectWait");
        Objects.requireNonNull(listenerExecutor, "listenerExecutor");
        Objects.requireNonNull(log, "log");

        Duration loginTimeout;
        try {
            loginTimeout = loginTimeout(env);
        } catch (IllegalArgumentException e) {
            return new BootstrapResult.ConfigError(e.getMessage());
        }

        Backend backend;
        try {
            backend = BackendFactory.create(env, dataFolder);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return new BootstrapResult.ConfigError(e.getMessage()); // names the variable, never a value
        }
        Optional<InMemoryNetwork> network = backend instanceof InMemoryNetwork.Node n
                ? Optional.of(n.network()) : Optional.empty();
        String nodeId = nodeId(env, backend);

        List<AutoCloseable> built = new ArrayList<>();
        built.add(backend);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, daemon("pantheon-outbox"));
        ExecutorService writerCallbacks = daemonSingle("pantheon-writer");
        try {
            Files.createDirectories(dataFolder);
            Clock clock = Clock.systemUTC(); // one clock for the outbox, the writer and the drainer
            Outbox outbox = Outbox.open(dataFolder.resolve(OUTBOX_FILE), clock, log);
            built.add(outbox);
            DocWriter writer = new DocWriter(backend, outbox, clock, writerCallbacks, log);
            OutboxDrainer drainer = new OutboxDrainer(outbox, backend, writer, clock, scheduler, log);
            built.add(drainer);
            PlayerDataEngine players = new PlayerDataEngine(backend, writer, listenerExecutor, log);
            built.add(players);
            StoreRegistry stores = new StoreRegistry(backend, writer, listenerExecutor, log);
            built.add(stores);
            PresenceCache presence = new PresenceCache(backend);
            ReconnectSync sync = new ReconnectSync(backend, players, stores, drainer, log);
            built.add(sync);

            ConnectionState outcome = startAndWait(backend, sync, firstConnectWait);
            if (outcome == ConnectionState.FATAL) {
                closeQuietly(log, built.reversed().toArray(AutoCloseable[]::new));
                scheduler.shutdownNow();
                writerCallbacks.shutdownNow();
                return new BootstrapResult.ConfigError("Agora rejected node '" + nodeId
                        + "' (FATAL: UNAUTHENTICATED or PERMISSION_DENIED): check " + BackendFactory.NODE_ID
                        + " and the token in PANTHEON_AGORA_TOKEN");
            }
            if (network.isPresent()) {
                sync.sync(); // connected from the start: no change to CONNECTED will trigger it
            } else if (outcome != ConnectionState.CONNECTED) {
                log.warning("Agora is not reachable yet (state " + outcome + " after " + firstConnectWait.toMillis()
                        + " ms); starting anyway and connecting in the background. Logins are refused until then.");
            }
            return new BootstrapResult.Started(nodeId, backend, network, clock, outbox, writer, drainer, players,
                    stores, presence, sync, loginTimeout, scheduler,
                    concat(List.of(writerCallbacks), owned), log);
        } catch (RuntimeException | java.io.IOException e) {
            closeQuietly(log, built.reversed().toArray(AutoCloseable[]::new));
            scheduler.shutdownNow();
            writerCallbacks.shutdownNow();
            return new BootstrapResult.ConfigError("Pantheon could not start: " + e.getMessage());
        }
    }

    /** Starts the backend and waits for CONNECTED or FATAL, at most {@code wait}; returns the state then. */
    private static ConnectionState startAndWait(Backend backend, ReconnectSync sync, Duration wait) {
        CompletableFuture<ConnectionState> first = new CompletableFuture<>();
        Subscription s = backend.onConnectionState(state -> {
            if (state == ConnectionState.CONNECTED || state == ConnectionState.FATAL) first.complete(state);
        });
        try {
            backend.start(sync);
            ConnectionState now = backend.state();
            if (now == ConnectionState.CONNECTED || now == ConnectionState.FATAL) first.complete(now);
            try {
                return first.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return backend.state();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return backend.state();
            } catch (java.util.concurrent.ExecutionException e) {
                return backend.state();
            }
        } finally {
            s.unsubscribe();
        }
    }

    static Duration loginTimeout(Map<String, String> env) {
        String raw = env.getOrDefault(LOGIN_TIMEOUT_MS, "").trim();
        if (raw.isEmpty()) return DEFAULT_LOGIN_TIMEOUT;
        try {
            long ms = Long.parseLong(raw);
            if (ms <= 0) throw new NumberFormatException();
            return Duration.ofMillis(ms);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(LOGIN_TIMEOUT_MS + " must be a positive number of milliseconds, was '"
                    + raw + "'");
        }
    }

    private static String nodeId(Map<String, String> env, Backend backend) {
        if (backend instanceof InMemoryNetwork.Node n) return n.nodeId();
        String id = env.getOrDefault(BackendFactory.NODE_ID, "").trim();
        return id.isEmpty() ? BackendFactory.DEFAULT_NODE_ID : id;
    }

    private static List<ExecutorService> concat(List<ExecutorService> a, List<ExecutorService> b) {
        List<ExecutorService> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    static void closeQuietly(Logger log, AutoCloseable... parts) {
        for (AutoCloseable part : parts) {
            try {
                part.close();
            } catch (Exception e) {
                log.log(Level.WARNING, "Closing " + part.getClass().getSimpleName() + " failed", e);
            }
        }
    }

    private static ExecutorService daemonSingle(String name) {
        return Executors.newSingleThreadExecutor(daemon(name));
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
