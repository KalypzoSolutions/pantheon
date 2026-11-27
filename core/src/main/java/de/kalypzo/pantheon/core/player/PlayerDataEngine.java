package de.kalypzo.pantheon.core.player;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.data.ChangeDispatcher;
import de.kalypzo.pantheon.core.data.DocHandle;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.data.FieldInfo;
import de.kalypzo.pantheon.core.data.LiveDoc;
import de.kalypzo.pantheon.core.data.QueryImpl;
import de.kalypzo.pantheon.core.data.RemoteDocImpl;
import de.kalypzo.pantheon.core.data.RowDiff;
import de.kalypzo.pantheon.core.data.SnapshotDoc;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.DocSnapshot;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.IndexDeclaration;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.Predicate;
import de.kalypzo.pantheon.core.data.wire.QueryTarget;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.TransferResult;
import de.kalypzo.pantheon.data.DataUnavailableException;
import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.PlayerFields;
import de.kalypzo.pantheon.data.Query;
import de.kalypzo.pantheon.data.RemoteDoc;
import de.kalypzo.pantheon.data.Subscription;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The player data of this node (player.md §5-§8, data.md §3, §5, §6). {@link #load} makes this node holder of the
 * player, reads all namespaces in one call and stores the name; until {@link #release} the profile is a
 * {@link LiveDoc} on the player's {@link DocHandle}. {@code DocChanged} of players that are not held is ignored
 * (A-10). Every write goes through the {@link DocWriter}, so pending ops survive a release.
 *
 * <p>{@code onChange} listeners (E15) fire for own writes, confirmed conditional requests and transfers, foreign
 * changes and resyncs of loaded players, only if the field's value changes, always on {@code listenerExecutor} (paper
 * passes the main thread). For P-22 the reconnect sync uses {@link #heldPlayers()} and {@link #resync}.
 * Thread-safe.
 */
public final class PlayerDataEngine implements AutoCloseable {

    private static final FieldInfo<String> NAME = FieldInfo.of(PlayerFields.NAME);
    private static final QueryTarget PLAYERS = new QueryTarget.Players();

    private final Backend backend;
    private final DocWriter writer;
    private final Logger log;
    private final ChangeDispatcher<UUID> changes;

    private final Object lock = new Object();
    private final Map<UUID, Held> held = new ConcurrentHashMap<>(); // written under lock
    /** Players this node sent {@code releasePlayer} for; the next load waits for a stream barrier first (R45). */
    private final Set<UUID> released = ConcurrentHashMap.newKeySet();
    private final Subscription docChanged;
    private final Subscription diffs;

    public PlayerDataEngine(Backend backend, DocWriter writer, Executor listenerExecutor, Logger log) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.log = Objects.requireNonNull(log, "log");
        this.changes = new ChangeDispatcher<>(Objects.requireNonNull(listenerExecutor, "listenerExecutor"), log);
        this.docChanged = backend.onDocChanged(this::onDocChanged);
        this.diffs = writer.onDiff(this::onDiff);
    }

    /**
     * Becomes holder, reads every namespace and sets {@code PlayerFields.NAME} if it differs. Fails with
     * {@link DataUnavailableException} if the backend is unavailable or does not answer within {@code timeout}; the
     * player is not held then.
     */
    public CompletableFuture<Doc> load(UUID player, String name, Duration timeout) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(timeout, "timeout");
        Held h;
        boolean afterRelease;
        synchronized (lock) { // R45: every release of this node is either sent and marked, or not sent yet
            h = held.get(player);
            if (h == null) {
                PlayerDoc ref = new PlayerDoc(player);
                DocHandle handle = writer.acquire(ref);
                h = new Held(player, ref, handle, new LiveDoc(player.toString(), handle, target(player)));
                held.put(player, h);
            }
            afterRelease = released.remove(player);
        }
        Held entry = h;
        CompletableFuture<Void> barrier = afterRelease ? backend.streamBarrier()
                : CompletableFuture.completedFuture(null);
        CompletableFuture<List<Row>> call = barrier.thenCompose(v -> backend.loadPlayer(player));
        CompletableFuture<Doc> result = new CompletableFuture<>();
        call.copy().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((rows, e) -> {
            if (e != null) {
                failed(entry, call, timeout, unwrap(e), result);
            } else {
                loaded(entry, name, rows, call, result);
            }
        });
        return result;
    }

    /** The profile while the player is loaded. */
    public Optional<Doc> profile(UUID player) {
        Held h = held.get(player);
        return h != null && h.loaded ? Optional.of(h.doc) : Optional.empty();
    }

    /** No longer holder; the profile leaves memory. Ops still open keep running in the background. */
    public void release(UUID player) {
        synchronized (lock) {
            Held h = held.remove(player);
            if (h != null) releaseHolder(h);
        }
    }

    /** Writes without loading. */
    public RemoteDoc remote(UUID player) {
        Objects.requireNonNull(player, "player");
        return new RemoteDocImpl(player.toString(), target(player));
    }

    /** The player's data as stored, queued behind this node's own ops on the player; empty if there is none. */
    public CompletableFuture<Optional<Doc>> loadSnapshot(UUID player) {
        PlayerDoc ref = new PlayerDoc(Objects.requireNonNull(player, "player"));
        return writer.enqueueCall(ref, key -> backend.load(ref)).thenApply(rows -> rows.isEmpty()
                ? Optional.<Doc>empty()
                : Optional.of(SnapshotDoc.of(player.toString(), target(player), rows)));
    }

    /** A query over all players (E9); hits are snapshots with every namespace. */
    public Query query() {
        return new QueryImpl(backend, PLAYERS, field -> Objects.requireNonNull(field, "field"), this::hit);
    }

    /** E10: the number of players with a larger value plus one; equal values share a rank. */
    public CompletableFuture<Long> rank(Field<? extends Number> field, UUID player) {
        Objects.requireNonNull(player, "player");
        @SuppressWarnings("unchecked")
        FieldInfo<Number> info = (FieldInfo<Number>) (FieldInfo<?>) FieldInfo.of(field);
        Optional<Doc> profile = profile(player);
        CompletableFuture<Optional<Number>> value = profile.isPresent()
                ? CompletableFuture.completedFuture(Optional.ofNullable(profile.get().get(info.field())))
                : loadSnapshot(player).thenApply(doc -> doc.isPresent()
                        ? Optional.ofNullable(doc.get().get(info.field()))
                        : Optional.ofNullable(info.field().defaultValue()));
        return value.thenCompose(v -> {
            // without a value the player is behind everyone who has one (NULLS LAST)
            Predicate greater = v.isPresent() ? new Predicate.Gt(info.encode(v.get())) : new Predicate.Exists();
            return backend.count(PLAYERS, List.of(new FieldCondition(info.spec(), greater)));
        }).thenApply(n -> n + 1);
    }

    /** Withdraws from {@code from} and credits {@code to} in one transaction; loaded profiles are updated. */
    public CompletableFuture<Boolean> transfer(Field<Long> field, UUID from, UUID to, long amount) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return writer.transfer(new PlayerDoc(from), new PlayerDoc(to), FieldInfo.of(field).spec(), amount)
                .thenApply(TransferResult::transferred);
    }

    public CompletableFuture<Void> index(Field<?>... fields) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Field<?> f : fields) {
            futures.add(backend.declareIndex(new IndexDeclaration(PLAYERS, FieldInfo.of(f).spec(), false)));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /** E15: runs on the listener executor, only for players loaded on this node and only if the value changes. */
    public <T> Subscription onChange(Field<T> field, PlayerChangeListener<T> listener) {
        Objects.requireNonNull(listener, "listener");
        return changes.add(field, listener::changed);
    }

    /** Every player this node holds or is loading, for {@code NodeState} and the reconnect sync. */
    public Collection<UUID> heldPlayers() {
        return List.copyOf(held.keySet());
    }

    /**
     * P-22: the known version per namespace of a held player (namespaces with only pending ops: 0); empty if the
     * player is not held. The reconnect sync compares it with the backend's versions (E7).
     */
    public Map<String, Long> versions(UUID player) {
        Held h = held.get(player);
        if (h == null) return Map.of();
        Map<String, Long> out = new HashMap<>();
        for (String ns : h.handle.namespaces()) out.put(ns, h.handle.version(ns));
        return out;
    }

    /**
     * P-22: the rows of a held player after a reconnect, merged by version ({@link DocHandle#merge}): a row is adopted
     * if its version is larger, pending ops are replayed on top, known namespaces missing from {@code rows} stay
     * (player rows are never deleted, and a change event newer than the read is kept). Listeners fire for what
     * changed. A player that is not held is ignored.
     */
    public void resync(UUID player, List<Row> rows) {
        Held h = held.get(player);
        if (h == null) return;
        synchronized (h) {
            report(h, h.handle.merge(rows));
        }
    }

    /** Stops listening to the backend and the writer; held players stay held (shutdown releases them). */
    @Override
    public void close() {
        docChanged.unsubscribe();
        diffs.unsubscribe();
    }

    // ---------- load ----------

    private void loaded(Held h, String name, List<Row> rows, CompletableFuture<List<Row>> call,
                        CompletableFuture<Doc> result) {
        synchronized (h) {
            if (held.get(h.player) != h) { // released while loading
                lateHolder(h.player, call);
                result.completeExceptionally(new DataUnavailableException(
                        "player " + h.player + " was released while loading"));
                return;
            }
            List<RowDiff> diffs = new ArrayList<>(h.handle.merge(rows)); // keeps rows that arrived while loading
            if (!name.equals(NAME.raw(h.handle.view(PlayerFields.PANTHEON.name())))) {
                diffs.addAll(writer.write(h.ref, List.of(new FieldOp.Set(NAME.spec(), NAME.encode(name)))));
            }
            if (h.loaded) {
                report(h, diffs); // loaded again while held: what changed is a change
            } else {
                Map<String, Map<String, Object>> views = new HashMap<>();
                for (String ns : h.handle.namespaces()) views.put(ns, h.handle.view(ns));
                changes.seed(h.player, views); // the baseline of later diffs, also of late acks of an old handle
                h.loaded = true;
            }
        }
        result.complete(h.doc);
    }

    private void failed(Held h, CompletableFuture<List<Row>> call, Duration timeout, Throwable cause,
                        CompletableFuture<Doc> result) {
        Throwable error = cause instanceof TimeoutException
                ? new DataUnavailableException("loading player " + h.player + " timed out after " + timeout.toMillis()
                        + " ms")
                : cause;
        synchronized (lock) {
            if (held.get(h.player) == h && !h.loaded) {
                held.remove(h.player);
                releaseHolder(h);
                lateHolder(h.player, call);
            }
        }
        log.log(Level.WARNING, "Loading player " + h.player + " failed: " + error);
        result.completeExceptionally(error);
    }

    /** A load that was given up can still register this node at the backend later: undo that. */
    private void lateHolder(UUID player, CompletableFuture<List<Row>> call) {
        call.thenRun(() -> {
            synchronized (lock) { // a load either sees the mark (and waits for the barrier) or the release is not sent
                if (!held.containsKey(player)) {
                    released.add(player);
                    backend.releasePlayer(player);
                }
            }
        });
    }

    /** Callers hold {@code lock}: marking and sending are one step for a concurrent load (R45). */
    private void releaseHolder(Held h) {
        changes.reset(h.player);
        writer.release(h.ref);
        released.add(h.player);
        backend.releasePlayer(h.player); // fire-and-forget, cheap under the lock
    }

    // ---------- writes and changes ----------

    private RemoteDocImpl.Target target(UUID player) {
        PlayerDoc ref = new PlayerDoc(player);
        return new RemoteDocImpl.Target() {
            @Override
            public void write(List<FieldOp> ops) {
                Held h = held.get(player);
                if (h == null) {
                    writer.write(ref, ops);
                    return;
                }
                synchronized (h) { // computing and reporting the diff in one step keeps listeners in order
                    report(h, writer.write(ref, ops));
                }
            }

            @Override
            public CompletableFuture<ApplyResult> writeConditional(List<FieldOp> ops, List<FieldCondition> conditions) {
                return writer.writeConditional(ref, ops, conditions); // the ack arrives as a diff
            }
        };
    }

    private void onDiff(DocRef ref, List<RowDiff> diffs) {
        if (!(ref instanceof PlayerDoc p)) return;
        Held h = held.get(p.player());
        if (h == null) return;
        synchronized (h) {
            report(h, diffs);
        }
    }

    private void onDocChanged(DocChanged c) {
        if (!(c.ref() instanceof PlayerRow row)) return;
        Held h = held.get(row.player());
        if (h == null) return; // A-10: a player this node does not hold (any more)
        synchronized (h) {
            report(h, h.handle.accept(c));
        }
    }

    /** Callers hold the monitor of {@code h}. */
    private void report(Held h, List<RowDiff> diffs) {
        if (!h.loaded || diffs.isEmpty() || held.get(h.player) != h) return;
        changes.changed(h.player, diffs, h.handle::view);
    }

    private Optional<Doc> hit(DocSnapshot s) {
        if (!(s.doc() instanceof PlayerDoc p) || s.rows().isEmpty()) return Optional.empty();
        return Optional.of(SnapshotDoc.of(p.player().toString(), target(p.player()), s.rows()));
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static final class Held {
        final UUID player;
        final PlayerDoc ref;
        final DocHandle handle;
        final LiveDoc doc;
        volatile boolean loaded; // written under the monitor of this

        Held(UUID player, PlayerDoc ref, DocHandle handle, LiveDoc doc) {
            this.player = player;
            this.ref = ref;
            this.handle = handle;
            this.doc = doc;
        }
    }
}
