package de.kalypzo.pantheon.core.sync;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.ConnectionState;
import de.kalypzo.pantheon.core.backend.NodeState;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.RowRef;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.outbox.OutboxDrainer;
import de.kalypzo.pantheon.core.player.PlayerDataEngine;
import de.kalypzo.pantheon.core.store.StoreRegistry;
import de.kalypzo.pantheon.data.Subscription;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The reconnect sync (agora.md §3 Zustellung, §8, E7, E23): what this node missed while it was disconnected is fetched
 * again on every change to {@code CONNECTED} (R49: the {@code BarrierAck} after the Hello). It is also the
 * {@link NodeState} the backend is started with (R11): the held players come from the {@link PlayerDataEngine}, the
 * watched stores from the {@link StoreRegistry}.
 *
 * <p>One run, on a thread of its own (never the backend's stream thread; {@link StoreRegistry#reloadCached} blocks up
 * to 10 s per store):
 * <ol>
 *   <li>{@link OutboxDrainer#drain()} and wait for it, at most {@link #DRAIN_WAIT}.
 *   <li>{@code versions(rows = ∅, players = held players)}: the versions of every row of each held player (E7), so a
 *       namespace created during the outage is seen too.
 *   <li>Each player with a row whose version differs from the known one, or a row that is not known, is read with
 *       {@code load(PlayerDoc)} and merged with {@link PlayerDataEngine#resync} (by version, pending ops on top).
 *   <li>{@link StoreRegistry#reloadCached()}: cached stores are loaded completely (E23), deletions included.
 * </ol>
 *
 * <p><b>Why the outbox first.</b> An outbox entry can already be applied at the backend while its answer was lost
 * (deadline, connection cut); its ops are still pending in the {@link de.kalypzo.pantheon.core.data.DocHandle}. Merged
 * before the replay, the newer row would contain the ops once and the pending replay a second time, until the replay's
 * ack drops them. Draining first acks (and drops) those ops, so the version check sees rows that already contain them
 * and the view never counts an op twice. Ops still pending afterwards (written after the drain) are not lost either:
 * {@code merge} adopts newer rows and replays pending ops on top, so a resync never overwrites local writes. If the
 * drain does not finish within {@link #DRAIN_WAIT} (a large outbox), the run goes on anyway, because of that
 * property; the end value is correct, only a double count can be visible until the replay's ack.
 *
 * <p>Holder registration needs no {@code loadPlayer}: the Hello reports every held player ({@link #heldPlayers}), so
 * the run reads with {@code load}, which does not register; R45 (barrier after a release) does not apply. A player
 * released during the run is skipped by {@code resync}. The presence is replaced by {@code PresenceCache} at
 * {@code Welcome} (P-15), not here.
 *
 * <p>Runs are serialized; a newer {@code CONNECTED} supersedes a running one, which stops at its next step. A run that
 * fails while connected (e.g. a deadline) is repeated after 1 s, doubling up to 30 s. A memory backend is connected
 * from the start and never reports a change: call {@link #sync()} once after {@code start}. Thread-safe.
 */
public final class ReconnectSync implements NodeState, AutoCloseable {

    /** How long a run waits for the outbox replay before it checks the versions anyway. */
    public static final Duration DRAIN_WAIT = Duration.ofSeconds(30);
    /** The backend's unary deadline is 5 s; this is only the guard for a future that never completes. */
    static final Duration CALL_WAIT = Duration.ofSeconds(6);
    static final Duration MIN_RETRY = Duration.ofSeconds(1);
    static final Duration MAX_RETRY = Duration.ofSeconds(30);

    private final Backend backend;
    private final PlayerDataEngine engine;
    private final StoreRegistry stores;
    private final OutboxDrainer drainer;
    private final Logger log;
    private final ScheduledExecutorService executor;
    private final Subscription connection;

    private final AtomicLong generation = new AtomicLong();
    private final Object lock = new Object();
    private @Nullable ConnectionState last;   // guarded by lock
    private Duration retry = MIN_RETRY;        // guarded by lock
    private volatile boolean closed;

    public ReconnectSync(Backend backend, PlayerDataEngine engine, StoreRegistry stores, OutboxDrainer drainer) {
        this(backend, engine, stores, drainer, Logger.getLogger(ReconnectSync.class.getName()));
    }

    public ReconnectSync(Backend backend, PlayerDataEngine engine, StoreRegistry stores, OutboxDrainer drainer,
                         Logger log) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.stores = Objects.requireNonNull(stores, "stores");
        this.drainer = Objects.requireNonNull(drainer, "drainer");
        this.log = Objects.requireNonNull(log, "log");
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pantheon-reconnect-sync");
            t.setDaemon(true);
            return t;
        });
        synchronized (lock) {
            last = backend.state();
        }
        this.connection = backend.onConnectionState(this::onState);
    }

    @Override
    public Collection<UUID> heldPlayers() {
        return engine.heldPlayers();
    }

    @Override
    public Collection<StoreRef> watchedStores() {
        return stores.watchedStores();
    }

    /**
     * Starts a run now (superseding a running one) and returns when it is done; called on every change to
     * {@code CONNECTED}. Completes normally when superseded, exceptionally if the run failed (a retry is scheduled).
     */
    public CompletableFuture<Void> sync() {
        long gen = generation.incrementAndGet();
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (closed) {
            done.completeExceptionally(new IllegalStateException("reconnect sync is closed"));
            return done;
        }
        try {
            executor.execute(() -> run(gen, done));
        } catch (RejectedExecutionException e) {
            done.completeExceptionally(new IllegalStateException("reconnect sync is closed", e));
        }
        return done;
    }

    @Override
    public void close() {
        closed = true;
        connection.unsubscribe();
        generation.incrementAndGet();
        executor.shutdownNow();
    }

    // ---------- internals ----------

    private void onState(ConnectionState state) {
        boolean reconnected;
        synchronized (lock) {
            reconnected = state == ConnectionState.CONNECTED && last != ConnectionState.CONNECTED;
            last = state;
            if (reconnected) retry = MIN_RETRY;
        }
        if (reconnected) sync(); // only queues the run: never blocks the backend's thread
    }

    private boolean stale(long gen) {
        return closed || generation.get() != gen || backend.state() != ConnectionState.CONNECTED;
    }

    private void run(long gen, CompletableFuture<Void> done) {
        try {
            if (!stale(gen)) {
                runSteps(gen);
                synchronized (lock) {
                    retry = MIN_RETRY;
                }
            }
            done.complete(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done.completeExceptionally(e);
        } catch (Exception e) {
            done.completeExceptionally(e);
            if (stale(gen)) return; // the next CONNECTED starts a new run
            Duration delay;
            synchronized (lock) {
                delay = retry;
                retry = retry.multipliedBy(2).compareTo(MAX_RETRY) > 0 ? MAX_RETRY : retry.multipliedBy(2);
            }
            log.log(Level.WARNING, "Reconnect sync failed, retrying in " + delay.toSeconds() + " s: " + e);
            try {
                executor.schedule(() -> {
                    if (generation.get() == gen) sync();
                }, delay.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException closing) {
                // closed meanwhile
            }
        }
    }

    private void runSteps(long gen) throws Exception {
        // 1. own writes of the outage first (see class doc)
        try {
            drainer.drain().get(DRAIN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warning("Outbox not replayed within " + DRAIN_WAIT.toSeconds() + " s, checking versions anyway");
        } catch (CancellationException | ExecutionException e) {
            log.log(Level.WARNING, "Outbox replay did not finish: " + e);
        }
        if (stale(gen)) return;

        // 2. versions of every row of each held player (E7)
        List<UUID> players = List.copyOf(engine.heldPlayers());
        List<Exception> failures = new ArrayList<>();
        if (!players.isEmpty()) {
            Map<RowRef, Long> remote = backend.versions(List.of(), players)
                    .get(CALL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            if (stale(gen)) return;

            // 3. read and merge the players with a changed or new row
            Map<UUID, CompletableFuture<List<Row>>> loads = new LinkedHashMap<>();
            for (UUID p : changed(players, remote)) loads.put(p, backend.load(new PlayerDoc(p)));
            for (Map.Entry<UUID, CompletableFuture<List<Row>>> e : loads.entrySet()) {
                try {
                    engine.resync(e.getKey(), e.getValue().get(CALL_WAIT.toMillis(), TimeUnit.MILLISECONDS));
                } catch (ExecutionException | TimeoutException ex) {
                    failures.add(ex);
                    log.log(Level.WARNING, "Reconnect sync could not read player " + e.getKey() + ": " + ex);
                }
            }
            if (stale(gen)) return;
        }

        // 4. cached stores completely (E23); logs its own failures and keeps the old content
        stores.reloadCached();

        if (!failures.isEmpty()) {
            Exception first = failures.getFirst();
            for (int i = 1; i < failures.size(); i++) first.addSuppressed(failures.get(i));
            throw first;
        }
    }

    /** Players with a row whose version differs from the known one, or that is not known at all. */
    private List<UUID> changed(List<UUID> players, Map<RowRef, Long> remote) {
        Map<UUID, Map<String, Long>> byPlayer = new HashMap<>();
        for (Map.Entry<RowRef, Long> e : remote.entrySet()) {
            if (e.getKey() instanceof PlayerRow r) {
                byPlayer.computeIfAbsent(r.player(), k -> new HashMap<>()).put(r.namespace(), e.getValue());
            }
        }
        List<UUID> out = new ArrayList<>();
        for (UUID p : players) {
            Map<String, Long> rows = byPlayer.getOrDefault(p, Map.of());
            Map<String, Long> known = engine.versions(p);
            for (Map.Entry<String, Long> row : rows.entrySet()) {
                if (!row.getValue().equals(known.getOrDefault(row.getKey(), 0L))) {
                    out.add(p);
                    break;
                }
            }
        }
        return out;
    }
}
