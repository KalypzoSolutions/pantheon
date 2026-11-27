package de.kalypzo.pantheon.core.backend.agora;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import de.kalypzo.agora.proto.v1.Barrier;
import de.kalypzo.agora.proto.v1.CountRequest;
import de.kalypzo.agora.proto.v1.CreateRequest;
import de.kalypzo.agora.proto.v1.DeclareIndexRequest;
import de.kalypzo.agora.proto.v1.DeleteRequest;
import de.kalypzo.agora.proto.v1.ExpireRequest;
import de.kalypzo.agora.proto.v1.Heartbeat;
import de.kalypzo.agora.proto.v1.Hello;
import de.kalypzo.agora.proto.v1.LoadPlayerRequest;
import de.kalypzo.agora.proto.v1.LoadRequest;
import de.kalypzo.agora.proto.v1.LocateRequest;
import de.kalypzo.agora.proto.v1.LocationReply;
import de.kalypzo.agora.proto.v1.LocationRequest;
import de.kalypzo.agora.proto.v1.NodeKind;
import de.kalypzo.agora.proto.v1.NodeMessage;
import de.kalypzo.agora.proto.v1.QueryRequest;
import de.kalypzo.agora.proto.v1.ReleasePlayer;
import de.kalypzo.agora.proto.v1.RowVersion;
import de.kalypzo.agora.proto.v1.SendDelivery;
import de.kalypzo.agora.proto.v1.ServiceMessage;
import de.kalypzo.agora.proto.v1.TransferRequest;
import de.kalypzo.agora.proto.v1.UnwatchStore;
import de.kalypzo.agora.proto.v1.VersionsRequest;
import de.kalypzo.agora.proto.v1.WatchStore;
import de.kalypzo.agora.proto.v1.Welcome;
import de.kalypzo.pantheon.agora.AgoraConnection;
import de.kalypzo.pantheon.agora.ConnectionListener;
import de.kalypzo.pantheon.agora.NodeConfig;
import de.kalypzo.pantheon.agora.NodeStateSupplier;
import de.kalypzo.pantheon.core.audit.AuditBundle;
import de.kalypzo.pantheon.core.audit.TimelineResult;
import de.kalypzo.pantheon.core.audit.TimelineSpec;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.ConnectionState;
import de.kalypzo.pantheon.core.backend.Delivery;
import de.kalypzo.pantheon.core.backend.LocationResolver;
import de.kalypzo.pantheon.core.backend.NodeState;
import de.kalypzo.pantheon.core.backend.PresenceChange;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.CreateResult;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.DocSnapshot;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.IndexDeclaration;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.QuerySpec;
import de.kalypzo.pantheon.core.data.wire.QueryTarget;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.RowRef;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.data.wire.TransferResult;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.data.DataUnavailableException;
import de.kalypzo.pantheon.data.DuplicateValueException;
import de.kalypzo.pantheon.data.Subscription;
import de.kalypzo.pantheon.player.ExpectedOnlinePlayerException;
import de.kalypzo.pantheon.world.NetworkLocation;
import io.grpc.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The production {@link Backend}: all three ports over one {@link AgoraConnection} (agora.md §2, §8, §9).
 *
 * <ul>
 *   <li>Unary calls carry the connection's 5 s deadline. Without a connection they fail at once with
 *       {@link DataUnavailableException}, without waiting for the deadline. Status mapping: {@code UNAVAILABLE},
 *       {@code DEADLINE_EXCEEDED} and other server-side failures → {@link DataUnavailableException};
 *       {@code ALREADY_EXISTS} → {@link DuplicateValueException} (index = description); {@code INVALID_ARGUMENT},
 *       {@code OUT_OF_RANGE} → {@link IllegalArgumentException}; {@code FAILED_PRECONDITION}, {@code UNIMPLEMENTED}
 *       → {@link IllegalStateException}.
 *   <li>State: {@code CONNECTING} after {@link #start}, {@code CONNECTED} once Agora acknowledged the barrier the
 *       connection sends right after Hello (R49: auth passed, Hello handled; Welcome is not awaited),
 *       {@code DISCONNECTED} when the stream ends (the connection reconnects with backoff), {@code FATAL} on
 *       {@code UNAUTHENTICATED}/{@code PERMISSION_DENIED} of the stream (no reconnect; such a node is never
 *       CONNECTED). A unary UNAUTHENTICATED/PERMISSION_DENIED is reported as {@link DataUnavailableException}: the
 *       stream decides about FATAL.
 *   <li>Hello/Heartbeat report the {@link NodeState} given to {@link #start} together with what this backend
 *       registered itself ({@link #loadPlayer}, {@link #watchStore}, minus releases), so a heartbeat never drops a
 *       holder this node still has. {@code DocChanged} for players/stores this node does not hold is ignored.
 *   <li>Presence: {@code Welcome} replaces the copy ({@link PresenceChange.Replaced}), {@code PresenceChanged}
 *       upserts/removes. During an outage the old copy stays.
 *   <li>{@link #streamBarrier()}: sends {@code Barrier(request_id)} on the stream; Agora answers
 *       {@code BarrierAck} on the node's lane, i.e. after Agora handled every earlier message of this node (R45). It
 *       fails with {@link DataUnavailableException} when not connected, when the stream ends first, or after 5 s.
 *       The future completes on the gRPC thread; {@code DocChanged} events received before the ack may still be
 *       queued for the listeners (the barrier orders this node's messages at Agora, not the local dispatch).
 * </ul>
 *
 * Listeners run on one event thread of this backend, in stream order. Thread-safe. The token never appears in
 * {@link #toString()}.
 */
public final class AgoraBackend implements Backend {

    private static final Logger LOG = Logger.getLogger(AgoraBackend.class.getName());

    static final long BARRIER_TIMEOUT_MILLIS = 5_000;

    private final String nodeId;
    private final AgoraConnection connection;
    private final ExecutorService events;
    private final List<Consumer<DocChanged>> docListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<PresenceChange>> presenceListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ConnectionState>> stateListeners = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<Void>> barriers = new ConcurrentHashMap<>();

    private final Object lock = new Object();
    private final Set<UUID> held = new LinkedHashSet<>();               // guarded by lock
    private final Set<StoreRef> watched = new LinkedHashSet<>();        // guarded by lock
    private final Map<UUID, PresenceEntry> presence = new LinkedHashMap<>(); // guarded by lock
    private @Nullable NodeState nodeState;                              // guarded by lock
    private ConnectionState state = ConnectionState.DISCONNECTED;       // guarded by lock
    private boolean started;                                            // guarded by lock
    private boolean closed;                                             // guarded by lock
    private volatile @Nullable LocationResolver resolver;

    public AgoraBackend(NodeConfig config) {
        this.nodeId = config.nodeId();
        this.events = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pantheon-agora-events-" + nodeId);
            t.setDaemon(true);
            return t;
        });
        this.connection = new AgoraConnection(config, NodeKind.PAPER, new StateSupplier(), new Listener());
    }

    public String nodeId() {
        return nodeId;
    }

    // ---------- Backend ----------

    @Override
    public void start(NodeState state) {
        Objects.requireNonNull(state, "state");
        synchronized (lock) {
            if (closed || started) return;
            started = true;
            nodeState = state;
            setState(ConnectionState.CONNECTING);
        }
        connection.start();
    }

    @Override
    public ConnectionState state() {
        synchronized (lock) {
            return state;
        }
    }

    @Override
    public Subscription onConnectionState(Consumer<ConnectionState> listener) {
        return subscribe(stateListeners, listener);
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
        }
        connection.close();
        failBarriers("backend closed");
        synchronized (lock) {
            setState(ConnectionState.DISCONNECTED);
        }
        events.shutdown();
    }

    @Override
    public String toString() {
        return "AgoraBackend[nodeId=" + nodeId + "]";
    }

    // ---------- DataBackend ----------

    @Override
    public CompletableFuture<List<Row>> loadPlayer(UUID player) {
        Objects.requireNonNull(player, "player");
        synchronized (lock) {
            held.add(player); // before the call: Agora registers the holder before it reads (agora §5)
        }
        return unary(() -> connection.stub().loadPlayer(
                        LoadPlayerRequest.newBuilder().setPlayer(player.toString()).build()),
                r -> rows(r.getRowsList()));
    }

    @Override
    public void releasePlayer(UUID player) {
        synchronized (lock) {
            held.remove(player);
        }
        // Lost while disconnected; the next Hello no longer lists the player.
        connection.send(NodeMessage.newBuilder()
                .setReleasePlayer(ReleasePlayer.newBuilder().setPlayer(player.toString())).build());
    }

    @Override
    public CompletableFuture<List<Row>> load(DocRef doc) {
        return unary(() -> connection.stub().load(LoadRequest.newBuilder().setDoc(ProtoMapper.toProto(doc)).build()),
                r -> rows(r.getRowsList()));
    }

    @Override
    public CompletableFuture<List<DocSnapshot>> query(QueryTarget target, QuerySpec spec) {
        return unary(() -> connection.stub().query(QueryRequest.newBuilder().setTarget(ProtoMapper.toProto(target))
                        .setSpec(ProtoMapper.toProto(spec)).build()),
                r -> r.getDocsList().stream().map(ProtoMapper::fromProto).toList());
    }

    @Override
    public CompletableFuture<Long> count(QueryTarget target, List<FieldCondition> where) {
        return unary(() -> {
            var b = CountRequest.newBuilder().setTarget(ProtoMapper.toProto(target));
            where.forEach(c -> b.addWhere(ProtoMapper.toProto(c)));
            return connection.stub().count(b.build());
        }, r -> r.getCount());
    }

    @Override
    public CompletableFuture<Map<RowRef, Long>> versions(Collection<RowRef> rows, Collection<UUID> players) {
        return unary(() -> {
            var b = VersionsRequest.newBuilder();
            rows.forEach(r -> b.addRows(ProtoMapper.toProto(r)));
            players.forEach(p -> b.addPlayers(p.toString()));
            return connection.stub().versions(b.build());
        }, r -> {
            Map<RowRef, Long> out = new LinkedHashMap<>();
            for (RowVersion v : r.getVersionsList()) out.put(ProtoMapper.fromProto(v.getRef()), v.getVersion());
            return out;
        });
    }

    @Override
    public CompletableFuture<ApplyResult> apply(WriteRequest request) {
        return unary(() -> connection.stub().apply(ProtoMapper.toProto(request)),
                r -> new ApplyResult(r.getApplied(), rows(r.getRowsList())));
    }

    @Override
    public CompletableFuture<CreateResult> create(String idempotencyKey, StoreDoc doc, Map<String, Object> data) {
        return unary(() -> {
            var b = CreateRequest.newBuilder().setIdempotencyKey(idempotencyKey).setDoc(storeDoc(doc));
            data.forEach((k, v) -> b.putData(k, ProtoMapper.toProtoValue(v)));
            return connection.stub().create(b.build());
        }, r -> new CreateResult(r.getCreated(), r.getCreated() && r.hasRow() ? ProtoMapper.fromProto(r.getRow()) : null));
    }

    @Override
    public CompletableFuture<Boolean> delete(String idempotencyKey, StoreDoc doc) {
        return unary(() -> connection.stub().delete(DeleteRequest.newBuilder().setIdempotencyKey(idempotencyKey)
                .setDoc(storeDoc(doc)).build()), r -> r.getDeleted());
    }

    @Override
    public CompletableFuture<Void> expire(String idempotencyKey, StoreDoc doc, Instant at) {
        return unary(() -> connection.stub().expire(ExpireRequest.newBuilder().setIdempotencyKey(idempotencyKey)
                .setDoc(storeDoc(doc)).setAtMillis(at.toEpochMilli()).build()), r -> null);
    }

    @Override
    public CompletableFuture<TransferResult> transfer(String idempotencyKey, DocRef from, DocRef to, FieldSpec field,
                                                      long amount) {
        return unary(() -> connection.stub().transfer(TransferRequest.newBuilder().setIdempotencyKey(idempotencyKey)
                        .setFrom(ProtoMapper.toProto(from)).setTo(ProtoMapper.toProto(to))
                        .setField(ProtoMapper.toProto(field)).setAmount(amount).build()),
                r -> new TransferResult(r.getTransferred(), rows(r.getRowsList())));
    }

    @Override
    public CompletableFuture<Void> declareIndex(IndexDeclaration index) {
        return unary(() -> connection.stub().declareIndex(DeclareIndexRequest.newBuilder().setIndex(
                de.kalypzo.agora.proto.v1.IndexDeclaration.newBuilder().setTarget(ProtoMapper.toProto(index.target()))
                        .setField(ProtoMapper.toProto(index.field())).setUnique(index.unique())).build()), r -> null);
    }

    @Override
    public void watchStore(StoreRef store) {
        synchronized (lock) {
            watched.add(store);
        }
        connection.send(NodeMessage.newBuilder()
                .setWatchStore(WatchStore.newBuilder().setStore(store.qualified())).build());
    }

    @Override
    public void unwatchStore(StoreRef store) {
        synchronized (lock) {
            watched.remove(store);
        }
        connection.send(NodeMessage.newBuilder()
                .setUnwatchStore(UnwatchStore.newBuilder().setStore(store.qualified())).build());
    }

    @Override
    public CompletableFuture<Void> streamBarrier() {
        String id = UUID.randomUUID().toString();
        CompletableFuture<Void> ack = new CompletableFuture<>();
        barriers.put(id, ack);
        if (!connection.send(NodeMessage.newBuilder().setBarrier(Barrier.newBuilder().setRequestId(id)).build())) {
            barriers.remove(id);
            return CompletableFuture.failedFuture(new DataUnavailableException("node " + nodeId + " is not connected"));
        }
        return ack.orTimeout(BARRIER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).handle((v, e) -> {
            barriers.remove(id);
            if (e == null) return null;
            Throwable cause = unwrap(e);
            if (cause instanceof TimeoutException) {
                throw new DataUnavailableException("stream barrier not acknowledged within 5 s");
            }
            throw cause instanceof RuntimeException re ? re : new CompletionException(cause);
        });
    }

    @Override
    public Subscription onDocChanged(Consumer<DocChanged> listener) {
        return subscribe(docListeners, listener);
    }

    // ---------- PresenceBackend ----------

    @Override
    public Collection<PresenceEntry> snapshot() {
        synchronized (lock) {
            return List.copyOf(presence.values());
        }
    }

    @Override
    public Subscription onPresenceChanged(Consumer<PresenceChange> listener) {
        return subscribe(presenceListeners, listener);
    }

    @Override
    public CompletableFuture<NetworkLocation> location(UUID player) {
        Objects.requireNonNull(player, "player");
        if (!connection.connected()) {
            return CompletableFuture.failedFuture(new DataUnavailableException("node " + nodeId + " is not connected"));
        }
        CompletableFuture<NetworkLocation> result = new CompletableFuture<>();
        ListenableFuture<de.kalypzo.agora.proto.v1.LocateResponse> call;
        try {
            call = connection.stub().locate(LocateRequest.newBuilder().setPlayer(player.toString()).build());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(map(e));
        }
        call.addListener(() -> {
            try {
                var r = call.get();
                if (r.getFound()) result.complete(ProtoMapper.fromProto(r.getLocation()));
                else result.completeExceptionally(offline(player));
            } catch (ExecutionException e) {
                Status.Code code = Status.fromThrowable(e.getCause()).getCode();
                // R42: the server of the player did not answer in time (or is not connected): not reachable
                if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                    result.completeExceptionally(offline(player));
                } else {
                    result.completeExceptionally(map(e.getCause()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.completeExceptionally(new DataUnavailableException("interrupted", e));
            } catch (RuntimeException e) {
                result.completeExceptionally(map(e));
            }
        }, MoreExecutors.directExecutor());
        return result;
    }

    @Override
    public void answerLocations(LocationResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    // ---------- MessageBackend ----------

    @Override
    public void deliver(UUID player, Delivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        // Fire-and-forget; lost while disconnected (agora §8).
        connection.send(NodeMessage.newBuilder().setSendDelivery(SendDelivery.newBuilder()
                .setPlayer(player.toString()).setDelivery(ProtoMapper.toProto(delivery))).build());
    }

    // ---------- AuditBackend ----------

    /**
     * {@code AppendAudit} with gzip (audit_log §7). Never blocks the caller (the AuditBuffer scheduler, the close
     * thread): the call is started on the future stub and the future always completes, at the latest with the 5 s
     * deadline. A bundle the wire cannot carry fails the future with {@link IllegalArgumentException}.
     */
    @Override
    public CompletableFuture<Void> append(AuditBundle bundle) {
        return unary(() -> connection.stub().withCompression("gzip").appendAudit(ProtoMapper.toProto(bundle)),
                r -> null);
    }

    /** {@code Timeline}; values are decoded type-true via the {@code AuditValue} oneof. */
    @Override
    public CompletableFuture<TimelineResult> timeline(TimelineSpec spec) {
        return unary(() -> connection.stub().timeline(ProtoMapper.toProto(spec)), ProtoMapper::fromProto);
    }

    // ---------- internals: unary calls ----------

    @FunctionalInterface
    private interface Call<R> {
        ListenableFuture<R> start();
    }

    private <R, T> CompletableFuture<T> unary(Call<R> call, Function<R, T> map) {
        if (!connection.connected()) {
            return CompletableFuture.failedFuture(new DataUnavailableException("node " + nodeId + " is not connected"));
        }
        ListenableFuture<R> f;
        try {
            f = call.start();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(map(e));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        f.addListener(() -> {
            try {
                result.complete(map.apply(f.get()));
            } catch (ExecutionException e) {
                result.completeExceptionally(map(e.getCause()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.completeExceptionally(new DataUnavailableException("interrupted", e));
            } catch (RuntimeException e) {
                result.completeExceptionally(map(e));
            }
        }, MoreExecutors.directExecutor());
        return result;
    }

    /** Status → port exception (see the class comment). Exceptions that carry no gRPC status pass unchanged. */
    static RuntimeException map(Throwable t) {
        if (!(t instanceof io.grpc.StatusRuntimeException) && !(t instanceof io.grpc.StatusException)) {
            return t instanceof RuntimeException re ? re : new DataUnavailableException(String.valueOf(t), t);
        }
        Status status = Status.fromThrowable(t);
        String description = status.getDescription() == null ? status.getCode().name() : status.getDescription();
        return switch (status.getCode()) {
            case ALREADY_EXISTS -> new DuplicateValueException(description);
            case INVALID_ARGUMENT, OUT_OF_RANGE -> new IllegalArgumentException(description, t);
            case FAILED_PRECONDITION, UNIMPLEMENTED -> new IllegalStateException(description, t);
            default -> new DataUnavailableException("Agora: " + status.getCode() + " " + description, t);
        };
    }

    private static List<Row> rows(List<de.kalypzo.agora.proto.v1.Row> rows) {
        List<Row> out = new ArrayList<>(rows.size());
        for (var r : rows) out.add(ProtoMapper.fromProto(r));
        return out;
    }

    private static de.kalypzo.agora.proto.v1.StoreDoc storeDoc(StoreDoc doc) {
        return ProtoMapper.toProto((DocRef) doc).getStoreDoc();
    }

    private static ExpectedOnlinePlayerException offline(UUID player) {
        return new ExpectedOnlinePlayerException(player, ExpectedOnlinePlayerException.Scope.NETWORK);
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    // ---------- internals: stream ----------

    private void failBarriers(String why) {
        for (String id : List.copyOf(barriers.keySet())) {
            CompletableFuture<Void> f = barriers.remove(id);
            if (f != null) f.completeExceptionally(new DataUnavailableException(why));
        }
    }

    /** Caller holds the lock: state changes are fired in the order they happen. */
    private void setState(ConnectionState next) {
        if (state == next) return;
        if (state == ConnectionState.FATAL) return; // final
        state = next;
        fire(stateListeners, next);
    }

    private void onDocChanged(DocChanged change) {
        boolean mine;
        synchronized (lock) {
            mine = switch (change.ref()) {
                case PlayerRow r -> held.contains(r.player());
                case StoreDoc d -> watched.contains(d.store());
            };
        }
        if (mine) fire(docListeners, change);
    }

    private void onWelcome(Welcome welcome) {
        synchronized (lock) {
            presence.clear();
            for (var e : welcome.getPresenceList()) {
                PresenceEntry entry = ProtoMapper.fromProto(e);
                presence.put(entry.player(), entry);
            }
            fire(presenceListeners, new PresenceChange.Replaced(presence.values()));
        }
    }

    private void onPresenceChanged(de.kalypzo.agora.proto.v1.PresenceChanged change) {
        synchronized (lock) {
            switch (change.getChangeCase()) {
                case UPSERT -> {
                    PresenceEntry e = ProtoMapper.fromProto(change.getUpsert());
                    presence.put(e.player(), e);
                    fire(presenceListeners, new PresenceChange.Upsert(e));
                }
                case REMOVED -> {
                    UUID p = UUID.fromString(change.getRemoved());
                    if (presence.remove(p) != null) fire(presenceListeners, new PresenceChange.Removed(p));
                }
                case CHANGE_NOT_SET -> {}
            }
        }
    }

    private void onLocationRequest(LocationRequest request) {
        LocationResolver r = resolver;
        UUID player;
        try {
            player = UUID.fromString(request.getPlayer());
        } catch (IllegalArgumentException e) {
            reply(request, Optional.empty());
            return;
        }
        if (r == null) {
            reply(request, Optional.empty());
            return;
        }
        CompletableFuture<Optional<NetworkLocation>> answer;
        try {
            answer = r.resolve(player);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Location resolver failed for " + player, e);
            reply(request, Optional.empty());
            return;
        }
        answer.whenComplete((found, e) -> {
            if (e != null) LOG.log(Level.WARNING, "Location resolver failed for " + player, e);
            reply(request, e == null && found != null ? found : Optional.empty());
        });
    }

    private void reply(LocationRequest request, Optional<NetworkLocation> found) {
        var b = LocationReply.newBuilder().setRequestId(request.getRequestId()).setFound(found.isPresent());
        found.ifPresent(l -> b.setLocation(ProtoMapper.toProto(l)));
        connection.send(NodeMessage.newBuilder().setLocationReply(b).build());
    }

    private <E> void fire(List<Consumer<E>> listeners, E event) {
        if (listeners.isEmpty()) return;
        try {
            events.execute(() -> {
                for (Consumer<E> l : listeners) {
                    try {
                        l.accept(event);
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "Listener of node " + nodeId + " failed", e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // closed
        }
    }

    private static <E> Subscription subscribe(List<Consumer<E>> listeners, Consumer<E> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Hello/Heartbeat: the node state from {@link #start} plus this backend's own registrations. */
    private final class StateSupplier implements NodeStateSupplier {

        @Override
        public Hello hello() {
            Holds h = holds();
            return Hello.newBuilder().setNodeId(nodeId).setKind(NodeKind.PAPER).addAllHeldPlayers(h.players())
                    .addAllWatchedStores(h.stores()).build();
        }

        @Override
        public Heartbeat heartbeat() {
            Holds h = holds();
            return Heartbeat.newBuilder().addAllHeldPlayers(h.players()).addAllWatchedStores(h.stores()).build();
        }

        private Holds holds() {
            NodeState s;
            Set<String> players = new LinkedHashSet<>();
            Set<String> stores = new LinkedHashSet<>();
            synchronized (lock) {
                s = nodeState;
                held.forEach(p -> players.add(p.toString()));
                watched.forEach(w -> stores.add(w.qualified()));
            }
            if (s != null) { // outside the lock: the node state belongs to the caller
                s.heldPlayers().forEach(p -> players.add(p.toString()));
                s.watchedStores().forEach(w -> stores.add(w.qualified()));
            }
            return new Holds(players, stores);
        }
    }

    private record Holds(Set<String> players, Set<String> stores) {}

    private final class Listener implements ConnectionListener {

        @Override
        public void onConnected() {
            synchronized (lock) {
                if (!closed) setState(ConnectionState.CONNECTED);
            }
        }

        @Override
        public void onWelcome(Welcome welcome) {
            AgoraBackend.this.onWelcome(welcome);
        }

        @Override
        public void onMessage(ServiceMessage m) {
            switch (m.getPayloadCase()) {
                case DOC_CHANGED -> onDocChanged(ProtoMapper.fromProto(m.getDocChanged()));
                case PRESENCE_CHANGED -> onPresenceChanged(m.getPresenceChanged());
                case LOCATION_REQUEST -> onLocationRequest(m.getLocationRequest());
                case BARRIER_ACK -> {
                    CompletableFuture<Void> f = barriers.get(m.getBarrierAck().getRequestId());
                    if (f != null) f.complete(null);
                }
                default -> LOG.fine(() -> "Ignoring " + m.getPayloadCase() + " from Agora");
            }
        }

        @Override
        public void onDisconnected(Status status) {
            LOG.log(Level.WARNING, "Agora connection of " + nodeId + " lost: " + status.getCode()
                    + (status.getDescription() == null ? "" : " " + status.getDescription()));
            synchronized (lock) {
                if (!closed) setState(ConnectionState.DISCONNECTED);
            }
            failBarriers("stream to Agora ended");
        }

        @Override
        public void onFatal(Status status) {
            LOG.severe("Agora rejected node " + nodeId + ": " + status.getCode() + ", no further reconnect");
            synchronized (lock) {
                setState(ConnectionState.FATAL);
            }
            failBarriers("Agora rejected the node");
        }
    }
}
