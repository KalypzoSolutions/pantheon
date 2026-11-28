package de.kalypzo.pantheon.core.backend.agora;

import static org.awaitility.Awaitility.await;

import de.kalypzo.agora.AgoraServer;
import de.kalypzo.agora.config.AgoraConfig;
import de.kalypzo.agora.proto.v1.ClaimPresenceRequest;
import de.kalypzo.agora.proto.v1.Heartbeat;
import de.kalypzo.agora.proto.v1.Hello;
import de.kalypzo.agora.proto.v1.KickResult;
import de.kalypzo.agora.proto.v1.NodeKind;
import de.kalypzo.agora.proto.v1.NodeMessage;
import de.kalypzo.agora.proto.v1.PlayerLeft;
import de.kalypzo.agora.proto.v1.PlayerSwitched;
import de.kalypzo.agora.proto.v1.ServiceMessage;
import de.kalypzo.agora.proto.v1.Welcome;
import de.kalypzo.pantheon.agora.AgoraConnection;
import de.kalypzo.pantheon.agora.ConnectionListener;
import de.kalypzo.pantheon.agora.NodeConfig;
import de.kalypzo.pantheon.agora.NodeConfigException;
import de.kalypzo.pantheon.agora.NodeStateSupplier;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.ConnectionState;
import de.kalypzo.pantheon.core.backend.ContractFixture;
import de.kalypzo.pantheon.core.backend.Delivered;
import de.kalypzo.pantheon.core.backend.NodeState;
import de.kalypzo.pantheon.core.backend.PresenceChange;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The contract fixture over a real, in-process Agora ({@link AgoraServer#start(AgoraConfig)}, port 0, no PAPER
 * warm-up) on a Testcontainers Postgres shared by all tests of the JVM. Every fixture starts its own Agora (fresh
 * presence and holders; the database is shared, the suites use fresh ids). Nodes are {@link AgoraBackend}s; the
 * proxy {@code proxy-01} is a raw VELOCITY {@link AgoraConnection} that claims, switches, leaves, answers kicks and
 * records every {@code Deliver}. {@link #outage()}/{@link #restore()} stop the server and start it again on the same
 * port.
 */
final class AgoraContractFixture implements ContractFixture {

    static final String PROXY = "proxy-01";
    private static final List<String> NODES = List.of("lobby-01", "lobby-02", "lobby-03", PROXY);
    private static final Duration WAIT = Duration.ofSeconds(10);

    /**
     * One container per JVM, stopped by Testcontainers' reaper at the end. The benchmarks pass an external database
     * ({@code bench.pg.url}, {@code bench.pg.user}, {@code bench.pg.password}) so every JMH fork shares one.
     */
    static final class Db {
        static final String URL;
        static final String USER;
        static final String PASSWORD;

        static {
            String url = System.getProperty("bench.pg.url");
            if (url != null) {
                URL = url;
                USER = System.getProperty("bench.pg.user");
                PASSWORD = System.getProperty("bench.pg.password");
            } else {
                PostgreSQLContainer c = new PostgreSQLContainer("postgres:17-alpine");
                c.start();
                URL = c.getJdbcUrl();
                USER = c.getUsername();
                PASSWORD = c.getPassword();
            }
        }
    }

    private final Path dir;
    private final AgoraConfig config;
    private volatile AgoraServer server;
    private final Map<String, AgoraBackend> nodes = new ConcurrentHashMap<>();
    private final Proxy proxy;

    AgoraContractFixture() {
        this(4);
    }

    /** The benchmarks use the production pool size (16) or sweep it. */
    AgoraContractFixture(int poolSize) {
        try {
            dir = Files.createTempDirectory("agora-contract");
            Path tokens = dir.resolve("tokens");
            StringBuilder sb = new StringBuilder("# E20: node_id=token\n");
            for (String n : NODES) sb.append(n).append("=tok-").append(n).append('\n');
            Files.writeString(tokens, sb.toString());
            AgoraConfig first = new AgoraConfig(0, Db.URL, Db.USER, Db.PASSWORD, poolSize, tokens, Duration.ZERO);
            server = AgoraServer.start(first);
            config = new AgoraConfig(server.port(), first.dbUrl(), first.dbUser(), first.dbPassword(),
                    first.dbPoolSize(), tokens, Duration.ZERO);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        proxy = new Proxy(nodeConfig(PROXY));
        proxy.start();
    }

    private String address() {
        return "localhost:" + config.port();
    }

    /** Benchmarks: the port of the running Agora (e.g. for a health check without SQL). */
    int port() {
        return config.port();
    }

    private NodeConfig nodeConfig(String id) {
        try {
            return NodeConfig.fromEnv(Map.of(NodeConfig.ENV_ADDRESS, address(), NodeConfig.ENV_NODE_ID, id,
                    NodeConfig.ENV_TOKEN, "tok-" + id));
        } catch (NodeConfigException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Backend node(String id) {
        return nodes.computeIfAbsent(id, this::startNode);
    }

    private AgoraBackend startNode(String id) {
        AgoraBackend b = new AgoraBackend(nodeConfig(id));
        CompletableFuture<Void> welcomed = new CompletableFuture<>();
        b.onPresenceChanged(c -> {
            if (c instanceof PresenceChange.Replaced) welcomed.complete(null);
        });
        b.start(EMPTY);
        // Welcome (no warm-up here) follows the session registration: from now on DocChanged reaches this node.
        try {
            welcomed.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("node " + id + " got no Welcome", e);
        }
        await().atMost(WAIT).until(() -> b.state() == ConnectionState.CONNECTED);
        return b;
    }

    @Override
    public void join(PresenceEntry entry) {
        proxy.join(entry);
    }

    @Override
    public void switchServer(UUID player, String server) {
        proxy.switchServer(player, server);
    }

    @Override
    public void leave(UUID player) {
        proxy.leave(player);
    }

    @Override
    public List<Delivered> deliveries(String proxyId) {
        return proxyId.equals(PROXY) ? List.copyOf(proxy.delivered) : List.of();
    }

    /** Benchmarks: every delivery goes to {@code hook} instead of the list behind {@link #deliveries}. */
    void onDelivered(Consumer<Delivered> hook) {
        proxy.hook = hook;
    }

    @Override
    public void outage() {
        server.close();
        await().atMost(WAIT).until(() -> nodes.values().stream().allMatch(n -> n.state() != ConnectionState.CONNECTED)
                && !proxy.connection.connected());
    }

    @Override
    public void restore() {
        server = AgoraServer.start(config);
    }

    @Override
    public void close() {
        nodes.values().forEach(AgoraBackend::close);
        proxy.connection.close();
        server.close();
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) Files.deleteIfExists(p);
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // temp dir
        }
    }

    private static final NodeState EMPTY = new NodeState() {
        @Override
        public Collection<UUID> heldPlayers() {
            return List.of();
        }

        @Override
        public Collection<StoreRef> watchedStores() {
            return List.of();
        }
    };

    /** The simulated Velocity proxy (A-11/A-12 from the proxy side). */
    private static final class Proxy implements ConnectionListener, NodeStateSupplier {
        final AgoraConnection connection;
        final List<Delivered> delivered = new CopyOnWriteArrayList<>();
        volatile @Nullable Consumer<Delivered> hook;
        private final Map<UUID, de.kalypzo.agora.proto.v1.PresenceEntry> players = new LinkedHashMap<>(); // by this
        private final String id;

        Proxy(NodeConfig config) {
            this.id = config.nodeId();
            this.connection = new AgoraConnection(config, NodeKind.VELOCITY, this, this);
        }

        void start() {
            connection.start();
            await().atMost(WAIT).until(connection::connected);
        }

        /** ClaimPresence; retried while the stream session is not registered yet (FAILED_PRECONDITION). */
        void join(PresenceEntry entry) {
            de.kalypzo.agora.proto.v1.PresenceEntry proto = ProtoMapper.toProto(entry);
            await().atMost(WAIT).pollInterval(Duration.ofMillis(50)).until(() -> {
                try {
                    boolean accepted = connection.stub().claimPresence(
                            ClaimPresenceRequest.newBuilder().setEntry(proto).build()).get(5, TimeUnit.SECONDS)
                            .getAccepted();
                    if (!accepted) throw new IllegalStateException("claim of " + entry.player() + " not accepted");
                    return true;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof StatusRuntimeException s
                            && s.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
                        return false;
                    }
                    throw e;
                }
            });
            synchronized (this) {
                players.put(entry.player(), proto.toBuilder().setProxy(id).build());
            }
        }

        void switchServer(UUID player, String server) {
            synchronized (this) {
                var e = players.get(player);
                if (e != null) players.put(player, e.toBuilder().setServer(server).build());
            }
            send(NodeMessage.newBuilder().setPlayerSwitched(
                    PlayerSwitched.newBuilder().setPlayer(player.toString()).setServer(server)).build());
        }

        void leave(UUID player) {
            synchronized (this) {
                players.remove(player);
            }
            send(NodeMessage.newBuilder().setPlayerLeft(PlayerLeft.newBuilder().setPlayer(player.toString())).build());
        }

        private void send(NodeMessage m) {
            if (!connection.send(m)) throw new IllegalStateException("proxy is not connected");
        }

        // ----- NodeStateSupplier: the full list of players on every (re)connect and heartbeat -----

        @Override
        public synchronized Hello hello() {
            return Hello.newBuilder().setNodeId(id).setKind(NodeKind.VELOCITY).addAllPlayers(players.values()).build();
        }

        @Override
        public synchronized Heartbeat heartbeat() {
            return Heartbeat.newBuilder().addAllPlayers(new ArrayList<>(players.values())).build();
        }

        // ----- ConnectionListener -----

        @Override
        public void onWelcome(Welcome welcome) {}

        @Override
        public void onMessage(ServiceMessage m) {
            switch (m.getPayloadCase()) {
                case DELIVER -> {
                    Delivered d = new Delivered(UUID.fromString(m.getDeliver().getPlayer()),
                            ProtoMapper.fromProto(m.getDeliver().getDelivery()));
                    Consumer<Delivered> h = hook;
                    if (h != null) h.accept(d);
                    else delivered.add(d);
                }
                case KICK_PLAYER -> {
                    boolean wasOnline;
                    synchronized (this) {
                        wasOnline = players.remove(UUID.fromString(m.getKickPlayer().getPlayer())) != null;
                    }
                    connection.send(NodeMessage.newBuilder().setKickResult(KickResult.newBuilder()
                            .setRequestId(m.getKickPlayer().getRequestId()).setWasOnline(wasOnline)).build());
                }
                default -> {}
            }
        }

        @Override
        public void onDisconnected(Status status) {}

        @Override
        public void onFatal(Status status) {}
    }
}
