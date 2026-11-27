package de.kalypzo.pantheon.agora;

import de.kalypzo.agora.proto.v1.AgoraGrpc;
import de.kalypzo.agora.proto.v1.BarrierAck;
import de.kalypzo.agora.proto.v1.Heartbeat;
import de.kalypzo.agora.proto.v1.Hello;
import de.kalypzo.agora.proto.v1.LocateRequest;
import de.kalypzo.agora.proto.v1.NodeKind;
import de.kalypzo.agora.proto.v1.NodeMessage;
import de.kalypzo.agora.proto.v1.ServiceMessage;
import de.kalypzo.agora.proto.v1.Welcome;
import io.grpc.ForwardingServerCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class AgoraConnectionTest {

    private static final Metadata.Key<String> NODE = Metadata.Key.of("x-agora-node", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTH = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final String name = "p09-" + UUID.randomUUID();
    private Server server;
    private ManagedChannel channel;
    private AgoraConnection connection;
    private ScheduledExecutorService scheduler;

    private final List<Metadata> seenHeaders = new CopyOnWriteArrayList<>();
    private final List<NodeMessage> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger streams = new AtomicInteger();
    private volatile StreamObserver<ServiceMessage> lastResponse;
    private volatile Status connectFailure;       // if set, Connect fails with it
    private volatile boolean unaryNeverAnswers;
    private volatile boolean sendWelcome = true;
    private volatile boolean ackBarriers = true;
    private final List<ServiceMessage> forwarded = new CopyOnWriteArrayList<>();
    private final List<Status> disconnected = new CopyOnWriteArrayList<>();
    private final List<Status> fatal = new CopyOnWriteArrayList<>();
    private final AtomicInteger welcomes = new AtomicInteger();
    private final List<Boolean> connectedAfterHello = new CopyOnWriteArrayList<>();

    private final NodeConfig config = config();

    private static NodeConfig config() {
        try {
            return NodeConfig.fromEnv(Map.of("PANTHEON_AGORA_ADDRESS", "x:1", "PANTHEON_NODE_ID", "lobby-01",
                    "PANTHEON_AGORA_TOKEN", "tok-123"));
        } catch (NodeConfigException e) {
            throw new IllegalStateException(e);
        }
    }

    private final AgoraGrpc.AgoraImplBase service = new AgoraGrpc.AgoraImplBase() {
        @Override
        public StreamObserver<NodeMessage> connect(StreamObserver<ServiceMessage> out) {
            streams.incrementAndGet();
            lastResponse = out;
            if (connectFailure != null) {
                out.onError(connectFailure.asRuntimeException());
                return new StreamObserver<>() {
                    public void onNext(NodeMessage m) { }
                    public void onError(Throwable t) { }
                    public void onCompleted() { }
                };
            }
            return new StreamObserver<>() {
                @Override
                public void onNext(NodeMessage m) {
                    received.add(m);
                    if (m.hasHello() && sendWelcome) {
                        out.onNext(ServiceMessage.newBuilder().setWelcome(Welcome.getDefaultInstance()).build());
                    }
                    if (m.hasBarrier() && ackBarriers) { // like Agora: on the lane, after the Hello (R49)
                        out.onNext(ServiceMessage.newBuilder().setBarrierAck(BarrierAck.newBuilder()
                                .setRequestId(m.getBarrier().getRequestId())).build());
                    }
                }
                public void onError(Throwable t) { }
                public void onCompleted() { }
            };
        }

        @Override
        public void locate(LocateRequest request, StreamObserver<de.kalypzo.agora.proto.v1.LocateResponse> out) {
            if (!unaryNeverAnswers) {
                out.onNext(de.kalypzo.agora.proto.v1.LocateResponse.getDefaultInstance());
                out.onCompleted();
            }
        }
    };

    private final NodeStateSupplier state = new NodeStateSupplier() {
        public Hello hello() {
            return Hello.newBuilder().setNodeId("lobby-01").setKind(NodeKind.PAPER).build();
        }
        public Heartbeat heartbeat() {
            return Heartbeat.getDefaultInstance();
        }
    };

    private final ConnectionListener listener = new ConnectionListener() {
        public void onWelcome(Welcome w) { welcomes.incrementAndGet(); }
        public void onMessage(ServiceMessage m) { forwarded.add(m); }
        public void onDisconnected(Status s) { disconnected.add(s); }
        public void onFatal(Status s) { fatal.add(s); }
        @Override public void onConnected() {
            connectedAfterHello.add(!received.isEmpty() && received.get(0).hasHello() && received.size() >= 2
                    && received.get(1).hasBarrier());
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                                                                         ServerCallHandler<ReqT, RespT> next) {
                seenHeaders.add(headers);
                return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(next.startCall(call, headers)) { };
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service, capture)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        scheduler = Executors.newScheduledThreadPool(2);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
        channel.shutdownNow();
        server.shutdownNow();
        scheduler.shutdownNow();
    }

    private AgoraConnection conn(Duration deadline, Duration heartbeat) {
        connection = new AgoraConnection(config, NodeKind.PAPER, state, listener, scheduler, deadline, heartbeat,
                channel, Clock.systemUTC(), RandomGenerator.getDefault());
        return connection;
    }

    @Test
    void sendsAuthMetadataOnEveryCall() throws Exception {
        conn(Duration.ofSeconds(5), Duration.ofSeconds(10)).start();
        await().until(() -> !received.isEmpty());
        connection.stub().locate(LocateRequest.getDefaultInstance()).get(2, TimeUnit.SECONDS);
        connection.stub().locate(LocateRequest.getDefaultInstance()).get(2, TimeUnit.SECONDS);

        assertThat(seenHeaders).hasSize(3); // stream + 2 unary
        for (Metadata h : seenHeaders) {
            assertThat(h.get(NODE)).isEqualTo("lobby-01");
            assertThat(h.get(AUTH)).isEqualTo("Bearer tok-123");
        }
    }

    @Test
    void helloIsFirstMessageAndRepeatedAfterReconnect() {
        conn(Duration.ofSeconds(5), Duration.ofMillis(100)).start();
        await().until(() -> received.size() >= 3);
        assertThat(received.get(0).hasHello()).isTrue();
        assertThat(received.get(1).hasBarrier()).isTrue(); // R49: the connect barrier right after Hello
        assertThat(received.get(2).hasHeartbeat()).isTrue();

        lastResponse.onError(Status.UNAVAILABLE.asRuntimeException()); // break the stream
        await().until(() -> !disconnected.isEmpty());
        received.clear(); // after the old stream ended (a late heartbeat of it raced a clear before), before the 1 s backoff
        await().atMost(5, TimeUnit.SECONDS).until(() -> streams.get() >= 2 && !received.isEmpty());
        assertThat(received.get(0).hasHello()).isTrue();
    }

    /**
     * R49: connected once the BarrierAck for the barrier sent right after Hello arrived (auth passed, Hello handled),
     * without waiting for Welcome; again after every reconnect. The ack of the connect barrier is not forwarded.
     */
    @Test
    void onConnectedAfterBarrierAckWithoutWelcome() {
        sendWelcome = false;
        conn(Duration.ofSeconds(5), Duration.ofSeconds(10)).start();
        await().atMost(3, TimeUnit.SECONDS).until(() -> connectedAfterHello.size() == 1);
        assertThat(connectedAfterHello).containsExactly(true);
        assertThat(connection.connected()).isTrue();
        assertThat(welcomes.get()).isZero();
        assertThat(forwarded).noneMatch(ServiceMessage::hasBarrierAck);

        received.clear();
        lastResponse.onError(Status.UNAVAILABLE.asRuntimeException());
        await().atMost(5, TimeUnit.SECONDS).until(() -> connectedAfterHello.size() == 2);
        assertThat(connectedAfterHello).containsExactly(true, true);
    }

    /** R49: without the ack the node is not connected; after the deadline the stream is dropped and retried. */
    @Test
    void noConnectedWithoutBarrierAck() throws Exception {
        ackBarriers = false;
        conn(Duration.ofMillis(300), Duration.ofSeconds(10)).start();
        await().atMost(3, TimeUnit.SECONDS).until(() -> !disconnected.isEmpty());
        assertThat(disconnected.getFirst().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        assertThat(connectedAfterHello).isEmpty();
        assertThat(connection.connected()).isFalse();
        await().atMost(5, TimeUnit.SECONDS).until(() -> streams.get() >= 2); // reconnect with backoff
        assertThat(connectedAfterHello).isEmpty();
    }

    /** A stream rejected by auth never reaches connected (R49). */
    @Test
    void unauthenticatedNeverConnected() throws Exception {
        connectFailure = Status.UNAUTHENTICATED;
        conn(Duration.ofSeconds(5), Duration.ofSeconds(10)).start();
        await().until(() -> !fatal.isEmpty());
        assertThat(connectedAfterHello).isEmpty();
    }

    /** Other barrier acks (the backend's own, R45) go to the listener. */
    @Test
    void foreignBarrierAckIsForwarded() {
        conn(Duration.ofSeconds(5), Duration.ofSeconds(10)).start();
        await().until(() -> connection.connected());
        assertThat(connection.send(NodeMessage.newBuilder()
                .setBarrier(de.kalypzo.agora.proto.v1.Barrier.newBuilder().setRequestId("mine")).build())).isTrue();
        await().until(() -> forwarded.stream().anyMatch(m -> m.hasBarrierAck()
                && m.getBarrierAck().getRequestId().equals("mine")));
    }

    @Test
    void heartbeatAtInterval() {
        conn(Duration.ofSeconds(5), Duration.ofMillis(100)).start();
        await().atMost(3, TimeUnit.SECONDS)
                .until(() -> received.stream().filter(NodeMessage::hasHeartbeat).count() >= 3);
    }

    @Test
    void unauthenticatedIsFatalAndStopsReconnect() throws Exception {
        connectFailure = Status.UNAUTHENTICATED;
        conn(Duration.ofSeconds(5), Duration.ofSeconds(10)).start();
        await().until(() -> !fatal.isEmpty());
        assertThat(fatal.get(0).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        Thread.sleep(1500); // would have reconnected after 1 s
        assertThat(streams.get()).isEqualTo(1);
        assertThat(connection.connected()).isFalse();
        assertThat(connection.send(NodeMessage.getDefaultInstance())).isFalse();
    }

    @Test
    void unaryCallsHaveDeadline() {
        unaryNeverAnswers = true;
        conn(Duration.ofMillis(200), Duration.ofSeconds(10));
        assertThatThrownBy(() -> connection.stub().locate(LocateRequest.getDefaultInstance()).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e.getCause()).getStatus().getCode())
                        .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    }

    @Test
    void reconnectBackoffCapsAt30s() {
        var noJitter = new FixedRandom(0.5); // 0.5 -> jitter 0
        List<Long> seq = new ArrayList<>();
        for (int i = 0; i < 9; i++) seq.add(AgoraConnection.backoff(i, noJitter).toMillis());
        assertThat(seq).containsExactly(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L, 30000L);
        assertThat(AgoraConnection.backoff(0, new FixedRandom(0.0)).toMillis()).isEqualTo(800L);
        assertThat(AgoraConnection.backoff(0, new FixedRandom(0.999999)).toMillis()).isBetween(1195L, 1200L);
        assertThat(AgoraConnection.backoff(40, new FixedRandom(0.999999)).toMillis()).isLessThanOrEqualTo(30000L);
    }

    @Test
    void reconnectUsesBackoffDelaysFromSchedulerAndResetsAfterStableConnection() {
        var recorder = new RecordingScheduler();
        var clock = new MutableClock();
        connectFailure = Status.UNAVAILABLE;
        connection = new AgoraConnection(config, NodeKind.PAPER, state, listener, recorder, Duration.ofSeconds(5),
                Duration.ofSeconds(10), channel, clock, new FixedRandom(0.5));
        connection.start();
        recorder.runNext();                     // first connect attempt -> fails -> schedules 1 s
        recorder.runNext();                     // -> 2 s
        recorder.runNext();                     // -> 4 s
        assertThat(recorder.delaysMillis).contains(1000L, 2000L, 4000L);

        connectFailure = null;
        recorder.runNext();                     // connects, stays up
        clock.advance(Duration.ofMinutes(5));
        recorder.delaysMillis.clear();
        lastResponse.onError(Status.UNAVAILABLE.asRuntimeException());
        assertThat(recorder.delaysMillis).contains(1000L); // reset because the stream was stable
    }

    @Test
    void connectedAfterStreamOpenAndHelloSentWithoutWaitingForWelcome() {
        sendWelcome = false; // R1: Welcome replaces presence only
        conn(Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThat(connection.connected()).isFalse();
        connection.start();
        await().until(() -> connection.connected());
        assertThat(received.get(0).hasHello()).isTrue();
        assertThat(welcomes.get()).isZero();
        assertThat(connection.send(NodeMessage.newBuilder().setHeartbeat(Heartbeat.getDefaultInstance()).build())).isTrue();

        lastResponse.onError(Status.UNAVAILABLE.asRuntimeException());
        await().until(() -> !disconnected.isEmpty());
    }

    @Test
    void welcomeIsForwardedToListener() {
        conn(Duration.ofSeconds(5), Duration.ofSeconds(10)).start();
        await().until(() -> welcomes.get() == 1);
    }

    @Test
    void closeStopsReconnect() throws Exception {
        conn(Duration.ofSeconds(5), Duration.ofSeconds(10)).start();
        await().until(() -> connection.connected());
        lastResponse.onError(Status.UNAVAILABLE.asRuntimeException());
        connection.close();
        Thread.sleep(1500); // a reconnect would have fired after 1 s
        assertThat(streams.get()).isEqualTo(1);
        assertThat(connection.connected()).isFalse();
    }

    @Test
    void queuedReconnectAfterCloseDoesNothing() {
        var recorder = new RecordingScheduler();
        connection = new AgoraConnection(config, NodeKind.PAPER, state, listener, recorder, Duration.ofSeconds(5),
                Duration.ofSeconds(10), channel, Clock.systemUTC(), new FixedRandom(0.5));
        connection.start();
        recorder.runNext();                 // connects
        assertThat(streams.get()).isEqualTo(1);
        lastResponse.onError(Status.UNAVAILABLE.asRuntimeException()); // queues reconnect
        connection.close();
        recorder.runNext();                 // queued reconnect runs after close
        assertThat(streams.get()).isEqualTo(1);
    }

    @Test
    void failingHelloSupplierOpensNoStreamAndReconnectsLater() {
        var recorder = new RecordingScheduler();
        NodeStateSupplier broken = new NodeStateSupplier() {
            public Hello hello() { throw new IllegalStateException("boom"); }
            public Heartbeat heartbeat() { return Heartbeat.getDefaultInstance(); }
        };
        connection = new AgoraConnection(config, NodeKind.PAPER, broken, listener, recorder, Duration.ofSeconds(5),
                Duration.ofSeconds(10), channel, Clock.systemUTC(), new FixedRandom(0.5));
        connection.start();
        recorder.runNext();
        assertThat(streams.get()).isZero();
        assertThat(disconnected).hasSize(1);
        assertThat(recorder.delaysMillis).contains(1000L);
    }

    @Test
    void startIsIdempotent() {
        var recorder = new RecordingScheduler();
        connection = new AgoraConnection(config, NodeKind.PAPER, state, listener, recorder, Duration.ofSeconds(5),
                Duration.ofSeconds(10), channel, Clock.systemUTC(), new FixedRandom(0.5));
        connection.start();
        connection.start();
        assertThat(recorder.delaysMillis).hasSize(1);
    }

    // -- helpers

    private static final class FixedRandom implements RandomGenerator {
        private final double v;
        FixedRandom(double v) { this.v = v; }
        public long nextLong() { return 0; }
        @Override public double nextDouble() { return v; }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.EPOCH;
        void advance(Duration d) { now = now.plus(d); }
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    /** Scheduler that records delays and runs tasks only on demand. */
    private static final class RecordingScheduler extends java.util.concurrent.AbstractExecutorService implements ScheduledExecutorService {
        final List<Long> delaysMillis = new ArrayList<>();
        private final java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        void runNext() { Runnable r = queue.poll(); if (r != null) r.run(); }
        @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable c, long d, TimeUnit u) {
            delaysMillis.add(u.toMillis(d)); queue.add(c); return new Fut();
        }
        @Override public <V> java.util.concurrent.ScheduledFuture<V> schedule(java.util.concurrent.Callable<V> c, long d, TimeUnit u) { throw new UnsupportedOperationException(); }
        @Override public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(Runnable c, long i, long p, TimeUnit u) { return new Fut(); }
        @Override public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable c, long i, long p, TimeUnit u) { return new Fut(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
        @Override public void execute(Runnable r) { queue.add(r); }
    }

    private static final class Fut implements java.util.concurrent.ScheduledFuture<Object> {
        public long getDelay(TimeUnit u) { return 0; }
        public int compareTo(Delayed o) { return 0; }
        public boolean cancel(boolean b) { return true; }
        public boolean isCancelled() { return false; }
        public boolean isDone() { return false; }
        public Object get() { return null; }
        public Object get(long t, TimeUnit u) { return null; }
    }
}
