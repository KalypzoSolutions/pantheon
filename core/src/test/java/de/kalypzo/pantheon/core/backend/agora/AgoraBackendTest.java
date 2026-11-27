package de.kalypzo.pantheon.core.backend.agora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import de.kalypzo.agora.proto.v1.AgoraGrpc;
import de.kalypzo.agora.proto.v1.AppendAuditResponse;
import de.kalypzo.agora.proto.v1.ApplyRequest;
import de.kalypzo.agora.proto.v1.AuditValue;
import de.kalypzo.agora.proto.v1.TimelineRequest;
import de.kalypzo.agora.proto.v1.TimelineResponse;
import de.kalypzo.agora.proto.v1.ApplyResponse;
import de.kalypzo.agora.proto.v1.BarrierAck;
import de.kalypzo.agora.proto.v1.LoadPlayerRequest;
import de.kalypzo.agora.proto.v1.LoadPlayerResponse;
import de.kalypzo.agora.proto.v1.LocateRequest;
import de.kalypzo.agora.proto.v1.LocateResponse;
import de.kalypzo.agora.proto.v1.NodeMessage;
import de.kalypzo.agora.proto.v1.ServiceMessage;
import de.kalypzo.pantheon.agora.NodeConfigException;
import de.kalypzo.pantheon.core.audit.AuditBundle;
import de.kalypzo.pantheon.core.audit.AuditRecord;
import de.kalypzo.pantheon.core.audit.TimelineResult;
import de.kalypzo.pantheon.core.audit.TimelineSpec;
import de.kalypzo.pantheon.core.audit.Viewer;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.BackendFactory;
import de.kalypzo.pantheon.core.backend.ConnectionState;
import de.kalypzo.pantheon.core.backend.NodeState;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.data.codec.FieldSpecs;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.data.DataUnavailableException;
import de.kalypzo.pantheon.data.DuplicateValueException;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.player.ExpectedOnlinePlayerException;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** AgoraBackend against a scripted gRPC server (no Postgres); the real Agora runs in the contract tests. */
class AgoraBackendTest {

    private static final Field<Long> COINS = DataNamespace.of("unit").ofLong("coins", 100);

    private Fake fake;
    private Backend backend;

    @AfterEach
    void tearDown() throws Exception {
        if (backend != null) backend.close();
        if (fake != null) fake.server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // ---------- card tests ----------

    @Test
    void notConnectedFailsFast() {
        backend = new AgoraBackend(config("localhost:1", "lobby-01")); // nothing listens there
        backend.start(empty());

        long start = System.nanoTime();
        Throwable failure = catchThrowable(() -> backend.apply(withdraw(UUID.randomUUID())).get(5, TimeUnit.SECONDS));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took).isLessThan(Duration.ofMillis(100));
        assertThat(failure).isInstanceOf(ExecutionException.class).cause().isInstanceOf(DataUnavailableException.class);
    }

    @Test
    void unauthenticatedBecomesFatal() throws Exception {
        fake = new Fake();
        fake.rejectConnect = Status.UNAUTHENTICATED;
        List<ConnectionState> states = new CopyOnWriteArrayList<>();
        backend = new AgoraBackend(config(fake.address(), "lobby-01"));
        backend.onConnectionState(states::add);
        backend.start(empty());

        await().atMost(Duration.ofSeconds(5)).until(() -> backend.state() == ConnectionState.FATAL);
        await().atMost(Duration.ofSeconds(5)).until(() -> states.contains(ConnectionState.FATAL));
        Thread.sleep(1500); // a reconnect would happen after about 1 s
        assertThat(backend.state()).isEqualTo(ConnectionState.FATAL);
        assertThat(states).doesNotContain(ConnectionState.CONNECTED); // R49: a rejected stream is never CONNECTED
        assertThat(cause(backend.apply(withdraw(UUID.randomUUID())))).isInstanceOf(DataUnavailableException.class);
    }

    @Test
    void factoryRequiresEnvForAgora() {
        for (Map<String, String> env : List.of(Map.<String, String>of(), Map.of("PANTHEON_BACKEND", "agora"),
                Map.of("PANTHEON_BACKEND", "agora", "PANTHEON_AGORA_ADDRESS", "localhost:9090",
                        "PANTHEON_NODE_ID", "lobby-01"))) {
            assertThatThrownBy(() -> BackendFactory.create(env, Path.of(".")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PANTHEON_")
                    .hasCauseInstanceOf(NodeConfigException.class);
        }
        try (Backend b = BackendFactory.create(Map.of("PANTHEON_BACKEND", "agora",
                "PANTHEON_AGORA_ADDRESS", "localhost:1", "PANTHEON_NODE_ID", "lobby-01",
                "PANTHEON_AGORA_TOKEN", "secret"), Path.of("."))) {
            assertThat(b).isInstanceOf(AgoraBackend.class);
            assertThat(b.state()).isEqualTo(ConnectionState.DISCONNECTED); // not started yet
            assertThat(b.toString()).doesNotContain("secret");
        }
    }

    @Test
    void factoryMemoryNeedsNoEnv() {
        try (Backend b = BackendFactory.create(Map.of("PANTHEON_BACKEND", "memory"), Path.of("."))) {
            assertThat(b).isInstanceOf(InMemoryNetwork.Node.class);
            assertThat(b.state()).isEqualTo(ConnectionState.CONNECTED);
        }
    }

    // ---------- further behaviour ----------

    /** R49: connected once the connect barrier after Hello is acknowledged; the (delayed) Welcome is not needed. */
    @Test
    void connectedAfterBarrierAckWithoutWelcome() throws Exception {
        fake = new Fake();
        fake.sendWelcome = false;
        UUID held = UUID.randomUUID();
        StoreRef watched = new StoreRef("unit", "clans");
        backend = new AgoraBackend(config(fake.address(), "lobby-01"));
        backend.start(state(List.of(held), List.of(watched)));

        await().atMost(Duration.ofSeconds(5)).until(() -> backend.state() == ConnectionState.CONNECTED);
        assertThat(fake.received.get(1).hasBarrier()).isTrue();
        NodeMessage hello = fake.received.getFirst();
        assertThat(hello.hasHello()).isTrue();
        assertThat(hello.getHello().getNodeId()).isEqualTo("lobby-01");
        assertThat(hello.getHello().getKind()).isEqualTo(de.kalypzo.agora.proto.v1.NodeKind.PAPER);
        assertThat(hello.getHello().getHeldPlayersList()).containsExactly(held.toString());
        assertThat(hello.getHello().getWatchedStoresList()).containsExactly("unit:clans");
    }

    @Test
    void statusCodesAreMapped() throws Exception {
        fake = new Fake();
        backend = connected("lobby-01");

        fake.applyFailure = Status.ALREADY_EXISTS.withDescription("pantheon_store_unit_owner");
        assertThat(cause(backend.apply(withdraw(UUID.randomUUID()))))
                .isInstanceOfSatisfying(DuplicateValueException.class,
                        e -> assertThat(e.index()).isEqualTo("pantheon_store_unit_owner"));
        fake.applyFailure = Status.INVALID_ARGUMENT.withDescription("bad");
        assertThat(cause(backend.apply(withdraw(UUID.randomUUID())))).isInstanceOf(IllegalArgumentException.class);
        fake.applyFailure = Status.UNAVAILABLE;
        assertThat(cause(backend.apply(withdraw(UUID.randomUUID())))).isInstanceOf(DataUnavailableException.class);
        fake.applyFailure = Status.DEADLINE_EXCEEDED;
        assertThat(cause(backend.apply(withdraw(UUID.randomUUID())))).isInstanceOf(DataUnavailableException.class);
    }

    /** R42: Locate UNAVAILABLE/DEADLINE_EXCEEDED or found=false → not online in the network. */
    @Test
    void locateFailuresAreNotOnline() throws Exception {
        fake = new Fake();
        backend = connected("lobby-01");
        UUID p = UUID.randomUUID();
        for (Status s : List.of(Status.UNAVAILABLE, Status.DEADLINE_EXCEEDED)) {
            fake.locateFailure = s;
            assertThat(cause(backend.location(p))).isInstanceOfSatisfying(ExpectedOnlinePlayerException.class,
                    e -> assertThat(e.scope()).isEqualTo(ExpectedOnlinePlayerException.Scope.NETWORK));
        }
        fake.locateFailure = null;
        assertThat(cause(backend.location(p))).isInstanceOf(ExpectedOnlinePlayerException.class);
    }

    @Test
    void docChangedOnlyForHeldPlayersAndWatchedStores() throws Exception {
        fake = new Fake();
        backend = connected("lobby-01");
        List<DocChanged> seen = new CopyOnWriteArrayList<>();
        backend.onDocChanged(seen::add);
        UUID held = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        StoreRef watched = new StoreRef("unit", "watched");
        backend.loadPlayer(held).get(5, TimeUnit.SECONDS);
        backend.watchStore(watched);

        fake.push(change(new PlayerRow(other, "unit")));
        fake.push(change(new StoreDoc(new StoreRef("unit", "other"), "x")));
        fake.push(change(new PlayerRow(held, "unit")));
        fake.push(change(new StoreDoc(watched, "x")));
        await().atMost(Duration.ofSeconds(5)).until(() -> seen.size() >= 2);
        assertThat(seen).extracting(DocChanged::ref)
                .containsExactly(new PlayerRow(held, "unit"), new StoreDoc(watched, "x"));

        backend.releasePlayer(held);
        backend.unwatchStore(watched);
        fake.push(change(new PlayerRow(held, "unit")));
        fake.push(change(new StoreDoc(watched, "x")));
        backend.loadPlayer(other).get(5, TimeUnit.SECONDS);
        fake.push(change(new PlayerRow(other, "unit"))); // marker
        await().atMost(Duration.ofSeconds(5)).until(() -> seen.size() >= 3);
        assertThat(seen).hasSize(3).last().extracting(DocChanged::ref).isEqualTo(new PlayerRow(other, "unit"));
    }

    /** R45: the barrier completes only with the ack, which Agora sends after the earlier messages of the stream. */
    @Test
    void streamBarrierWaitsForAck() throws Exception {
        fake = new Fake();
        backend = connected("lobby-01");
        fake.ackBarriers = false; // from now on (the connect barrier was acked)
        fake.received.clear();
        UUID p = UUID.randomUUID();

        backend.releasePlayer(p);
        CompletableFuture<Void> barrier = backend.streamBarrier();
        await().atMost(Duration.ofSeconds(5)).until(() -> fake.received.stream().anyMatch(NodeMessage::hasBarrier));
        List<NodeMessage> order = fake.received.stream()
                .filter(m -> m.hasReleasePlayer() || m.hasBarrier()).toList();
        assertThat(order).hasSize(2);
        assertThat(order.get(0).getReleasePlayer().getPlayer()).isEqualTo(p.toString());
        assertThat(barrier).isNotDone();

        fake.push(ServiceMessage.newBuilder().setBarrierAck(BarrierAck.newBuilder()
                .setRequestId(order.get(1).getBarrier().getRequestId())).build());
        barrier.get(5, TimeUnit.SECONDS);
    }

    @Test
    void streamBarrierFailsWhenStreamEnds() throws Exception {
        fake = new Fake();
        backend = connected("lobby-01");
        fake.ackBarriers = false;
        fake.received.clear();
        CompletableFuture<Void> barrier = backend.streamBarrier();
        await().atMost(Duration.ofSeconds(5)).until(() -> fake.received.stream().anyMatch(NodeMessage::hasBarrier));

        fake.out.onError(Status.UNAVAILABLE.asRuntimeException());

        assertThat(cause(barrier)).isInstanceOf(DataUnavailableException.class);
        assertThat(cause(new AgoraBackend(config("localhost:1", "x")).streamBarrier()))
                .isInstanceOf(DataUnavailableException.class);
    }

    /** The Hello after a reconnect carries what this backend registered itself (NodeState ∪ own holds). */
    @Test
    void reHelloCarriesOwnRegistrations() throws Exception {
        fake = new Fake();
        backend = connected("lobby-01");
        UUID p = UUID.randomUUID();
        UUID released = UUID.randomUUID();
        StoreRef store = new StoreRef("unit", "clans");
        backend.loadPlayer(p).get(5, TimeUnit.SECONDS);
        backend.loadPlayer(released).get(5, TimeUnit.SECONDS);
        backend.releasePlayer(released);
        backend.watchStore(store);
        fake.received.clear();

        fake.out.onError(Status.UNAVAILABLE.asRuntimeException());
        await().atMost(Duration.ofSeconds(5)).until(() -> fake.received.stream().anyMatch(NodeMessage::hasHello));
        var hello = fake.received.stream().filter(NodeMessage::hasHello).findFirst().orElseThrow().getHello();
        assertThat(hello.getHeldPlayersList()).containsExactly(p.toString());
        assertThat(hello.getWatchedStoresList()).containsExactly("unit:clans");
        await().atMost(Duration.ofSeconds(5)).until(() -> backend.state() == ConnectionState.CONNECTED);
    }

    @Test
    void appendUsesGzip() throws Exception {
        fake = new Fake();
        backend = connected("lobby-01");
        AuditRecord r = new AuditRecord(UUID.randomUUID(), UUID.randomUUID(), "shop:buy", null, null, null,
                Map.of("text", "hallo ".repeat(50)));

        backend.append(new AuditBundle(List.of(r), 2)).get(5, TimeUnit.SECONDS);

        Metadata headers = fake.headers.get("AppendAudit");
        assertThat(headers).isNotNull();
        assertThat(headers.get(Metadata.Key.of("grpc-encoding", Metadata.ASCII_STRING_MARSHALLER))).isEqualTo("gzip");
        assertThat(fake.appended).singleElement().satisfies(b -> {
            assertThat(b.getDropped()).isEqualTo(2);
            assertThat(ProtoMapper.fromProto(b.getEntries(0))).isEqualTo(r);
        });
    }

    /** append never blocks the caller (AuditBuffer scheduler, close thread) and its future always completes. */
    @Test
    void appendIsAsyncAndMapsStatus() throws Exception {
        fake = new Fake();
        backend = connected("lobby-01");
        AuditBundle bundle = new AuditBundle(List.of(new AuditRecord(UUID.randomUUID(), UUID.randomUUID(), "a:b",
                null, null, null, Map.of())), 0);

        fake.appendDelay = new CompletableFuture<>();
        long start = System.nanoTime();
        CompletableFuture<Void> pending = backend.append(bundle);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
        assertThat(pending).isNotNull().isNotDone();
        fake.appendDelay.complete(null);
        pending.get(5, TimeUnit.SECONDS);
        fake.appendDelay = null;

        fake.appendFailure = Status.INVALID_ARGUMENT.withDescription("bad");
        assertThat(cause(backend.append(bundle))).isInstanceOf(IllegalArgumentException.class);
        fake.appendFailure = Status.UNAVAILABLE;
        assertThat(cause(backend.append(bundle))).isInstanceOf(DataUnavailableException.class);
        fake.appendFailure = Status.DEADLINE_EXCEEDED;
        assertThat(cause(backend.append(bundle))).isInstanceOf(DataUnavailableException.class);
        fake.appendFailure = null;
        // a value the wire cannot carry fails the future, it does not throw
        AuditBundle bad = new AuditBundle(List.of(new AuditRecord(UUID.randomUUID(), UUID.randomUUID(), "a:b", null,
                null, null, Map.of("x", List.of()))), 0);
        assertThat(cause(backend.append(bad))).isInstanceOf(IllegalArgumentException.class);
        assertThat(cause(new AgoraBackend(config("localhost:1", "x")).append(bundle)))
                .isInstanceOf(DataUnavailableException.class);
    }

    @Test
    void timelineMapsRequestAndDecodesTypeTrue() throws Exception {
        fake = new Fake();
        backend = connected("lobby-01");
        UUID p = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        fake.timelineAnswer = TimelineResponse.newBuilder().addEntries(de.kalypzo.agora.proto.v1.StoredAuditEntry
                .newBuilder().setServer("lobby-02").setEntry(de.kalypzo.agora.proto.v1.AuditEntry.newBuilder()
                        .setId(ProtoMapper.toBytes(id)).setPlayer(ProtoMapper.toBytes(p)).setAction("shop:buy")
                        .putWith("l", AuditValue.newBuilder().setL(10_000_000L).build())
                        .putWith("d", AuditValue.newBuilder().setD(10_000_000.0).build())))
                .setCursor(ProtoMapper.toBytes(id)).build();
        TimelineSpec spec = new TimelineSpec(p, null, null, List.of("shop:*"), 1, null, null,
                new Viewer(null, "console", "command"));

        TimelineResult page = backend.timeline(spec).get(5, TimeUnit.SECONDS);

        assertThat(fake.timelineRequests).singleElement().isEqualTo(ProtoMapper.toProto(spec));
        assertThat(page.cursor()).isEqualTo(id);
        Map<String, Object> with = page.entries().getFirst().record().with();
        assertThat(with.get("l")).isInstanceOf(Long.class);
        assertThat(with.get("d")).isInstanceOf(Double.class);
        assertThat(page.entries().getFirst().server()).isEqualTo("lobby-02");

        fake.timelineFailure = Status.INVALID_ARGUMENT.withDescription("viewer fehlt");
        assertThat(cause(backend.timeline(spec))).isInstanceOf(IllegalArgumentException.class);
        fake.timelineFailure = Status.UNAVAILABLE;
        assertThat(cause(backend.timeline(spec))).isInstanceOf(DataUnavailableException.class);
    }

    // ---------- helpers ----------

    private Backend connected(String nodeId) {
        Backend b = new AgoraBackend(config(fake.address(), nodeId));
        b.start(empty());
        await().atMost(Duration.ofSeconds(5)).until(() -> b.state() == ConnectionState.CONNECTED);
        return b;
    }

    private static Throwable cause(CompletableFuture<?> f) {
        Throwable t = catchThrowable(() -> f.get(10, TimeUnit.SECONDS));
        assertThat(t).isInstanceOf(ExecutionException.class);
        return t.getCause();
    }

    private static ServiceMessage change(de.kalypzo.pantheon.core.data.wire.RowRef ref) {
        return ServiceMessage.newBuilder().setDocChanged(
                ProtoMapper.toProto(new DocChanged(ref, 1, false, Map.of(), null))).build();
    }

    private static WriteRequest withdraw(UUID p) {
        return new WriteRequest(UUID.randomUUID().toString(), new PlayerDoc(p),
                List.of(new FieldOp.Withdraw(FieldSpecs.of(COINS), 1)), List.of(), Instant.now());
    }

    static de.kalypzo.pantheon.agora.NodeConfig config(String address, String nodeId) {
        try {
            return de.kalypzo.pantheon.agora.NodeConfig.fromEnv(Map.of("PANTHEON_AGORA_ADDRESS", address,
                    "PANTHEON_NODE_ID", nodeId, "PANTHEON_AGORA_TOKEN", "tok-" + nodeId));
        } catch (NodeConfigException e) {
            throw new IllegalStateException(e);
        }
    }

    private static NodeState empty() {
        return state(List.of(), List.of());
    }

    private static NodeState state(List<UUID> players, List<StoreRef> stores) {
        return new NodeState() {
            @Override
            public Collection<UUID> heldPlayers() {
                return players;
            }

            @Override
            public Collection<StoreRef> watchedStores() {
                return stores;
            }
        };
    }

    /** A scripted Agora: records stream messages, answers barriers and a few unary calls. */
    private static final class Fake extends AgoraGrpc.AgoraImplBase {
        final List<NodeMessage> received = new CopyOnWriteArrayList<>();
        final Server server;
        volatile StreamObserver<ServiceMessage> out;
        volatile Status rejectConnect;
        volatile boolean sendWelcome = true;
        volatile boolean ackBarriers = true;
        volatile Status applyFailure;
        volatile Status locateFailure;
        final Map<String, Metadata> headers = new ConcurrentHashMap<>();
        final List<de.kalypzo.agora.proto.v1.AuditBundle> appended = new CopyOnWriteArrayList<>();
        volatile CompletableFuture<Void> appendDelay;
        volatile Status appendFailure;
        final List<TimelineRequest> timelineRequests = new CopyOnWriteArrayList<>();
        volatile TimelineResponse timelineAnswer = TimelineResponse.getDefaultInstance();
        volatile Status timelineFailure;

        Fake() throws Exception {
            ServerInterceptor recordHeaders = new ServerInterceptor() {
                @Override
                public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, Metadata h,
                                                                   ServerCallHandler<Q, A> next) {
                    headers.put(call.getMethodDescriptor().getBareMethodName(), h);
                    return next.startCall(call, h);
                }
            };
            server = NettyServerBuilder.forPort(0).addService(ServerInterceptors.intercept(this, recordHeaders))
                    .build().start();
        }

        @Override
        public void appendAudit(de.kalypzo.agora.proto.v1.AuditBundle request, StreamObserver<AppendAuditResponse> o) {
            Runnable answer = () -> {
                if (appendFailure != null) {
                    o.onError(appendFailure.asRuntimeException());
                    return;
                }
                appended.add(request);
                o.onNext(AppendAuditResponse.getDefaultInstance());
                o.onCompleted();
            };
            CompletableFuture<Void> delay = appendDelay;
            if (delay == null) answer.run();
            else delay.thenRun(answer);
        }

        @Override
        public void timeline(TimelineRequest request, StreamObserver<TimelineResponse> o) {
            timelineRequests.add(request);
            if (timelineFailure != null) {
                o.onError(timelineFailure.asRuntimeException());
                return;
            }
            o.onNext(timelineAnswer);
            o.onCompleted();
        }

        String address() {
            return "localhost:" + server.getPort();
        }

        synchronized void push(ServiceMessage m) {
            out.onNext(m);
        }

        @Override
        public StreamObserver<NodeMessage> connect(StreamObserver<ServiceMessage> responses) {
            if (rejectConnect != null) {
                responses.onError(rejectConnect.asRuntimeException());
            } else {
                out = responses;
            }
            return new StreamObserver<>() {
                @Override
                public void onNext(NodeMessage m) {
                    received.add(m);
                    if (m.hasHello() && sendWelcome) {
                        push(ServiceMessage.newBuilder()
                                .setWelcome(de.kalypzo.agora.proto.v1.Welcome.getDefaultInstance()).build());
                    }
                    if (m.hasBarrier() && ackBarriers) {
                        push(ServiceMessage.newBuilder().setBarrierAck(BarrierAck.newBuilder()
                                .setRequestId(m.getBarrier().getRequestId())).build());
                    }
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            };
        }

        @Override
        public void apply(ApplyRequest request, StreamObserver<ApplyResponse> o) {
            if (applyFailure != null) {
                o.onError(applyFailure.asRuntimeException());
                return;
            }
            o.onNext(ApplyResponse.newBuilder().setApplied(true).build());
            o.onCompleted();
        }

        @Override
        public void loadPlayer(LoadPlayerRequest request, StreamObserver<LoadPlayerResponse> o) {
            o.onNext(LoadPlayerResponse.getDefaultInstance());
            o.onCompleted();
        }

        @Override
        public void locate(LocateRequest request, StreamObserver<LocateResponse> o) {
            if (locateFailure != null) {
                o.onError(locateFailure.asRuntimeException());
                return;
            }
            o.onNext(LocateResponse.newBuilder().setFound(false).build());
            o.onCompleted();
        }
    }
}
