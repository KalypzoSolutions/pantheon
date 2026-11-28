package de.kalypzo.pantheon.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import de.kalypzo.agora.proto.v1.ClaimPresenceRequest;
import de.kalypzo.agora.proto.v1.ClaimPresenceResponse;
import de.kalypzo.agora.proto.v1.ConnectStatus;
import de.kalypzo.agora.proto.v1.NodeKind;
import de.kalypzo.agora.proto.v1.NodeMessage;
import de.kalypzo.agora.proto.v1.ServiceMessage;
import de.kalypzo.agora.proto.v1.Welcome;
import de.kalypzo.pantheon.agora.AgoraConnection;
import de.kalypzo.pantheon.agora.ConnectionListener;
import de.kalypzo.pantheon.agora.NodeConfig;
import de.kalypzo.pantheon.agora.NodeConfigException;
import io.grpc.Status;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** The proxy node: reports presence to Agora, answers kicks and hands deliveries to players. */
@Plugin(id = "pantheon", name = "Pantheon", version = BuildConstants.VERSION, authors = {"Kalypzo"})
public final class PantheonVelocityPlugin {

    private final ProxyServer server;
    private final Logger logger;

    /** True once Agora is not usable for good (bad config or UNAUTHENTICATED): all logins are denied. */
    private final AtomicBoolean unavailable = new AtomicBoolean();
    private volatile AgoraConnection connection;
    private volatile ProxyPresenceReporter reporter;

    @Inject
    public PantheonVelocityPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        NodeConfig config;
        try {
            config = NodeConfig.fromEnv(System.getenv());
        } catch (NodeConfigException e) {
            logger.error("Pantheon is not configured, all logins will be denied: {}", e.getMessage());
            unavailable.set(true);
            return;
        }
        ProxyPlayers players = new VelocityPlayers(server);
        ProxyDeliveryHandler delivery = new ProxyDeliveryHandler(players, logger);
        // the connection is created below; the link resolves it lazily
        AgoraLink link = new AgoraLink() {
            @Override
            public CompletableFuture<ClaimPresenceResponse> claimPresence(ClaimPresenceRequest request) {
                CompletableFuture<ClaimPresenceResponse> f = new CompletableFuture<>();
                AgoraConnection c = connection;
                if (c == null) {
                    f.completeExceptionally(new IllegalStateException("not connected"));
                    return f;
                }
                var future = c.stub().claimPresence(request);
                future.addListener(() -> {
                    try {
                        f.complete(future.get());
                    } catch (Exception e) {
                        f.completeExceptionally(e);
                    }
                }, Runnable::run);
                return f;
            }

            @Override
            public boolean send(NodeMessage message) {
                AgoraConnection c = connection;
                return c != null && c.send(message);
            }
        };
        ProxyKickHandler kicks = new ProxyKickHandler(players, link);
        ProxyConnectHandler connects = new ProxyConnectHandler(players, link);
        reporter = new ProxyPresenceReporter(config.nodeId(), players, link, Clock.systemUTC());
        connection = new AgoraConnection(config, NodeKind.VELOCITY, reporter.state(), new ConnectionListener() {
            @Override
            public void onWelcome(Welcome welcome) {
                // the proxy is the source of presence and ignores the snapshot
            }

            @Override
            public void onMessage(ServiceMessage message) {
                switch (message.getPayloadCase()) {
                    case KICK_PLAYER -> kicks.handle(message.getKickPlayer());
                    case CONNECT_PLAYER -> connects.handle(message.getConnectPlayer());
                    case DELIVER -> delivery.handle(message.getDeliver());
                    default -> { }
                }
            }

            @Override
            public void onDisconnected(Status status) {
                logger.warn("Agora connection lost: {}", status.getCode());
            }

            @Override
            public void onFatal(Status status) {
                logger.error("Agora rejected this node ({}), all logins will be denied", status.getCode());
                unavailable.set(true);
            }
        });
        connection.start();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        AgoraConnection c = connection;
        if (c != null) c.close();
    }

    @Subscribe(priority = 0, order = PostOrder.LAST)
    public EventTask onLogin(LoginEvent event) {
        if (!event.getResult().isAllowed()) return null;
        ProxyPresenceReporter r = reporter;
        if (unavailable.get() || r == null) {
            event.setResult(ResultedEvent.ComponentResult.denied(ProxyTexts.DATA_UNAVAILABLE));
            return null;
        }
        Player p = event.getPlayer();
        Locale locale = p.getEffectiveLocale();
        CompletableFuture<Boolean> claim = r.login(p, p.getUniqueId(), p.getUsername(),
                locale == null ? "" : locale.toString());
        return EventTask.resumeWhenComplete(claim.thenAccept(accepted -> {
            if (!accepted) event.setResult(ResultedEvent.ComponentResult.denied(ProxyTexts.DATA_UNAVAILABLE));
        }));
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        ProxyPresenceReporter r = reporter;
        if (r == null) return;
        Player p = event.getPlayer();
        r.switched(p, p.getUniqueId(), event.getServer().getServerInfo().getName());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        ProxyPresenceReporter r = reporter;
        if (r == null) return;
        Player p = event.getPlayer();
        r.left(p, p.getUniqueId());
    }

    /** Velocity-backed {@link ProxyPlayers}. */
    private static final class VelocityPlayers implements ProxyPlayers {
        private final ProxyServer server;

        VelocityPlayers(ProxyServer server) {
            this.server = server;
        }

        @Override
        public Collection<ProxyPlayer> online() {
            return server.getAllPlayers().stream().<ProxyPlayer>map(p -> new VelocityPlayer(p, server)).toList();
        }

        @Override
        public Optional<ProxyPlayer> find(UUID uuid) {
            return server.getPlayer(uuid).map(p -> new VelocityPlayer(p, server));
        }
    }

    private record VelocityPlayer(Player player, ProxyServer server) implements ProxyPlayer {
        @Override public UUID uuid() { return player.getUniqueId(); }
        @Override public String name() { return player.getUsername(); }

        @Override
        public Optional<String> currentServer() {
            return player.getCurrentServer().map(c -> c.getServerInfo().getName());
        }

        @Override
        public String locale() {
            Locale l = player.getEffectiveLocale();
            return l == null ? "" : l.toString();
        }

        @Override public void sendMessage(Component message) { player.sendMessage(message); }
        @Override public void sendActionBar(Component message) { player.sendActionBar(message); }
        @Override public void showTitle(Title title) { player.showTitle(title); }

        /** Velocity's player does not play sounds (Audience default is a no-op): discard. */
        @Override public boolean playSound(Sound sound) { return false; }

        @Override public void disconnect(Component reason) { player.disconnect(reason); }

        @Override
        public CompletableFuture<ConnectStatus> connect(String name) {
            try {
                Optional<RegisteredServer> target = server.getServer(name);
                if (target.isEmpty()) {
                    return CompletableFuture.completedFuture(ConnectStatus.FAILED);
                }
                return player.createConnectionRequest(target.get()).connect()
                        .thenApply(r -> ProxyConnectHandler.fromVelocity(r.getStatus()))
                        .exceptionally(e -> ConnectStatus.FAILED);
            } catch (RuntimeException e) {
                return CompletableFuture.completedFuture(ConnectStatus.FAILED);
            }
        }
    }
}
