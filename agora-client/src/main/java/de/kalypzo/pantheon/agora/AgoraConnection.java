package de.kalypzo.pantheon.agora;

import de.kalypzo.agora.proto.v1.AgoraGrpc;
import de.kalypzo.agora.proto.v1.Barrier;
import de.kalypzo.agora.proto.v1.NodeKind;
import de.kalypzo.agora.proto.v1.NodeMessage;
import de.kalypzo.agora.proto.v1.ServiceMessage;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

/**
 * The node's single connection to Agora: one channel, the {@code Connect} stream with reconnect and backoff.
 * Right after {@code Hello} the connection sends a {@code Barrier}; connected means its {@code BarrierAck} has
 * arrived (R49, refines R1): authentication passed and Agora handled the Hello. Welcome is not awaited. Without the
 * ack within the deadline the stream is dropped and retried. A stream rejected with UNAUTHENTICATED/PERMISSION_DENIED
 * never becomes connected. All public methods are thread-safe.
 */
public final class AgoraConnection implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AgoraConnection.class.getName());

    static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(5);
    static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(10);
    static final Duration KEEPALIVE = Duration.ofSeconds(10);
    static final int MAX_INBOUND_BYTES = 16 * 1024 * 1024;
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(1);
    private static final Duration BACKOFF_MAX = Duration.ofSeconds(30);
    private static final Duration STABLE_AFTER = Duration.ofSeconds(10);
    private static final double JITTER = 0.2;

    private static final Metadata.Key<String> NODE_KEY = Metadata.Key.of("x-agora-node", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTH_KEY = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final NodeKind kind;
    private final NodeStateSupplier state;
    private final ConnectionListener listener;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Duration deadline;
    private final Duration heartbeat;
    private final ManagedChannel rawChannel;
    private final Channel channel;
    private final Clock clock;
    private final RandomGenerator random;

    private final Object lock = new Object();
    private StreamObserver<NodeMessage> out;           // guarded by lock
    private long generation;                           // guarded by lock
    private boolean connected;                         // guarded by lock (BarrierAck received, R49)
    private @org.jspecify.annotations.Nullable String connectBarrier; // guarded by lock: awaited ack id
    private ScheduledFuture<?> barrierTimeout;         // guarded by lock
    private boolean stopped;                           // guarded by lock
    private boolean started;                           // guarded by lock
    private int attempt;                               // guarded by lock
    private Instant openedAt;                          // guarded by lock
    private ScheduledFuture<?> heartbeatTask;          // guarded by lock
    private ScheduledFuture<?> reconnectTask;          // guarded by lock

    public AgoraConnection(NodeConfig config, NodeKind kind, NodeStateSupplier state, ConnectionListener listener,
                           ScheduledExecutorService scheduler, Duration deadline, Duration heartbeat) {
        this(config, kind, state, listener, scheduler, deadline, heartbeat, buildChannel(config),
                Clock.systemUTC(), RandomGenerator.getDefault(), false);
    }

    public AgoraConnection(NodeConfig config, NodeKind kind, NodeStateSupplier state, ConnectionListener listener) {
        this(config, kind, state, listener, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agora-connection");
            t.setDaemon(true);
            return t;
        }), DEFAULT_DEADLINE, DEFAULT_HEARTBEAT, buildChannel(config), Clock.systemUTC(),
                RandomGenerator.getDefault(), true);
    }

    /** Test hook (R24): own channel, clock and randomness. */
    AgoraConnection(NodeConfig config, NodeKind kind, NodeStateSupplier state, ConnectionListener listener,
                    ScheduledExecutorService scheduler, Duration deadline, Duration heartbeat,
                    ManagedChannel channel, Clock clock, RandomGenerator random) {
        this(config, kind, state, listener, scheduler, deadline, heartbeat, channel, clock, random, false);
    }

    private AgoraConnection(NodeConfig config, NodeKind kind, NodeStateSupplier state, ConnectionListener listener,
                            ScheduledExecutorService scheduler, Duration deadline, Duration heartbeat,
                            ManagedChannel channel, Clock clock, RandomGenerator random, boolean ownsScheduler) {
        this.kind = kind;
        this.state = state;
        this.listener = listener;
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
        this.deadline = deadline;
        this.heartbeat = heartbeat;
        this.rawChannel = channel;
        this.channel = ClientInterceptors.intercept(channel, authInterceptor(config));
        this.clock = clock;
        this.random = random;
    }

    private static ManagedChannel buildChannel(NodeConfig config) {
        return NettyChannelBuilder.forTarget(config.address())
                .usePlaintext()
                .keepAliveTime(KEEPALIVE.toSeconds(), TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .maxInboundMessageSize(MAX_INBOUND_BYTES)
                .build();
    }

    private static ClientInterceptor authInterceptor(NodeConfig config) {
        String bearer = "Bearer " + config.token();
        String nodeId = config.nodeId();
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                       CallOptions options, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                        headers.put(NODE_KEY, nodeId);
                        headers.put(AUTH_KEY, bearer);
                        super.start(responseListener, headers);
                    }
                };
            }
        };
    }

    /** Backoff before reconnect attempt {@code attempt} (0-based): 1 s, 2 s, 4 s ... capped at 30 s, +-20 % jitter. */
    static Duration backoff(int attempt, RandomGenerator random) {
        long base = BACKOFF_BASE.toMillis() << Math.min(attempt, 20);
        base = Math.min(base, BACKOFF_MAX.toMillis());
        double factor = 1.0 + (random.nextDouble() * 2.0 - 1.0) * JITTER;
        return Duration.ofMillis(Math.min(BACKOFF_MAX.toMillis(), Math.round(base * factor)));
    }

    /** Starts connecting; returns immediately. */
    public void start() {
        synchronized (lock) {
            if (stopped || started) return;
            started = true;
            scheduleConnect(0);
        }
    }

    /** Caller holds the lock. A scheduler that is already shut down only matters while we are still running. */
    private void scheduleConnect(long delayMillis) {
        try {
            reconnectTask = scheduler.schedule(this::connect, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            if (!stopped) LOG.log(Level.WARNING, "Agora reconnect could not be scheduled", e);
        }
    }

    /** A new stub per call, carrying auth headers and the deadline. */
    public AgoraGrpc.AgoraFutureStub stub() {
        return AgoraGrpc.newFutureStub(channel).withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Sends on the stream; false if no stream is open. A message sent while the connect barrier is still pending
     * travels behind Hello and is lost if the stream is rejected.
     */
    public boolean send(NodeMessage message) {
        synchronized (lock) {
            if (out == null) return false;
            try {
                out.onNext(message);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /** True once the {@code BarrierAck} for the barrier sent right after {@code Hello} has arrived (R49). */
    public boolean connected() {
        synchronized (lock) {
            return connected;
        }
    }

    private void connect() {
        long gen;
        synchronized (lock) {
            if (stopped) return;
            gen = ++generation;
            connected = false;
        }
        StreamObserver<ServiceMessage> in = new StreamObserver<>() {
            @Override
            public void onNext(ServiceMessage m) {
                synchronized (lock) {
                    if (gen != generation || stopped) return;
                    if (m.hasBarrierAck() && connectBarrier != null
                            && connectBarrier.equals(m.getBarrierAck().getRequestId())) {
                        connectBarrier = null;
                        if (barrierTimeout != null) barrierTimeout.cancel(false);
                        connected = true;
                        try {
                            listener.onConnected();
                        } catch (RuntimeException e) {
                            LOG.log(Level.WARNING, "Agora listener failed", e);
                        }
                        return; // the connect barrier is ours, not the listener's
                    }
                }
                try {
                    if (m.getPayloadCase() == ServiceMessage.PayloadCase.WELCOME) {
                        listener.onWelcome(m.getWelcome());
                    } else {
                        listener.onMessage(m);
                    }
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Agora listener failed", e);
                }
            }

            @Override
            public void onError(Throwable t) {
                streamEnded(gen, Status.fromThrowable(t));
            }

            @Override
            public void onCompleted() {
                streamEnded(gen, Status.OK);
            }
        };
        NodeMessage hello;
        try {
            hello = NodeMessage.newBuilder().setHello(state.hello()).build(); // outside the lock
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Agora hello could not be built", e);
            streamEnded(gen, Status.INTERNAL.withCause(e));
            return;
        }
        StreamObserver<NodeMessage> stream = null;
        try {
            stream = AgoraGrpc.newStub(channel).connect(in);
            synchronized (lock) {
                if (gen != generation || stopped) {
                    cancel(stream);
                    return;
                }
                out = stream;
                stream.onNext(hello); // Hello is always first
                String barrierId = java.util.UUID.randomUUID().toString();
                connectBarrier = barrierId;
                stream.onNext(NodeMessage.newBuilder()
                        .setBarrier(Barrier.newBuilder().setRequestId(barrierId)).build()); // R49
                openedAt = clock.instant();
                try {
                    barrierTimeout = scheduler.schedule(() -> barrierTimedOut(gen, barrierId),
                            deadline.toMillis(), TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    LOG.log(Level.WARNING, "Agora barrier timeout could not be scheduled", e);
                }
                try {
                    heartbeatTask = scheduler.scheduleAtFixedRate(() -> sendHeartbeat(gen),
                            heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    LOG.log(Level.WARNING, "Agora heartbeat could not be scheduled", e);
                }
            }
        } catch (RuntimeException e) {
            if (stream != null) cancel(stream);
            streamEnded(gen, Status.fromThrowable(e));
        }
    }

    /** No BarrierAck within the deadline (R49): the stream counts as failed and is retried with backoff. */
    private void barrierTimedOut(long gen, String barrierId) {
        StreamObserver<NodeMessage> stream;
        synchronized (lock) {
            if (gen != generation || stopped || !barrierId.equals(connectBarrier)) return;
            stream = out;
        }
        streamEnded(gen, Status.DEADLINE_EXCEEDED.withDescription("no BarrierAck after Hello"));
        if (stream != null) cancel(stream);
    }

    private static void cancel(StreamObserver<NodeMessage> stream) {
        try {
            if (stream instanceof io.grpc.stub.ClientCallStreamObserver<NodeMessage> c) {
                c.cancel("closing stream", null);
            } else {
                stream.onError(Status.CANCELLED.asRuntimeException());
            }
        } catch (RuntimeException ignored) {
            // already closed
        }
    }

    private void sendHeartbeat(long gen) {
        synchronized (lock) {
            if (gen != generation || !connected) return;
        }
        NodeMessage message;
        try {
            message = NodeMessage.newBuilder().setHeartbeat(state.heartbeat()).build(); // outside the lock
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Agora heartbeat could not be built", e);
            return;
        }
        synchronized (lock) {
            if (gen != generation || !connected || out == null) return;
            try {
                out.onNext(message);
            } catch (RuntimeException ignored) {
                // stream failure is reported via onError
            }
        }
    }

    private void streamEnded(long gen, Status status) {
        boolean fatal = status.getCode() == Status.Code.UNAUTHENTICATED
                || status.getCode() == Status.Code.PERMISSION_DENIED;
        Duration delay = null;
        synchronized (lock) {
            if (gen != generation || stopped) return;
            generation++; // invalidates this stream
            connected = false;
            out = null;
            connectBarrier = null;
            if (heartbeatTask != null) heartbeatTask.cancel(false);
            if (barrierTimeout != null) barrierTimeout.cancel(false);
            if (openedAt != null && Duration.between(openedAt, clock.instant()).compareTo(STABLE_AFTER) >= 0) {
                attempt = 0;
            }
            openedAt = null;
            if (fatal) {
                stopped = true;
            } else {
                delay = backoff(attempt++, random);
                scheduleConnect(delay.toMillis());
            }
        }
        try {
            if (fatal) listener.onFatal(status);
            else listener.onDisconnected(status);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Agora listener failed", e);
        }
    }

    @Override
    public void close() {
        StreamObserver<NodeMessage> toClose;
        synchronized (lock) {
            if (stopped && out == null && rawChannel.isShutdown()) return;
            stopped = true;
            generation++;
            connected = false;
            toClose = out;
            out = null;
            connectBarrier = null;
            if (heartbeatTask != null) heartbeatTask.cancel(false);
            if (barrierTimeout != null) barrierTimeout.cancel(false);
            if (reconnectTask != null) reconnectTask.cancel(false);
        }
        if (toClose != null) {
            try {
                toClose.onCompleted();
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
        rawChannel.shutdown();
        try {
            if (!rawChannel.awaitTermination(200, TimeUnit.MILLISECONDS)) rawChannel.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rawChannel.shutdownNow();
        }
        if (ownsScheduler) scheduler.shutdownNow();
    }
}
