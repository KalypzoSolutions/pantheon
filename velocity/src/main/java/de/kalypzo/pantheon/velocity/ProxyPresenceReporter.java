package de.kalypzo.pantheon.velocity;

import de.kalypzo.agora.proto.v1.ClaimPresenceRequest;
import de.kalypzo.agora.proto.v1.Heartbeat;
import de.kalypzo.agora.proto.v1.Hello;
import de.kalypzo.agora.proto.v1.NodeKind;
import de.kalypzo.agora.proto.v1.NodeMessage;
import de.kalypzo.agora.proto.v1.PlayerLeft;
import de.kalypzo.agora.proto.v1.PlayerSwitched;
import de.kalypzo.agora.proto.v1.PresenceEntry;
import de.kalypzo.pantheon.agora.NodeStateSupplier;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Reports presence to Agora: claim at login, server switches, quit, and the full state for Hello/Heartbeat.
 * A session token (the Velocity player object) guards against a late event of an older connection of the same
 * UUID removing the newer connection's presence.
 */
public final class ProxyPresenceReporter {

    static final long CLAIM_DEADLINE_SECONDS = 5;

    private final String nodeId;
    private final ProxyPlayers players;
    private final AgoraLink link;
    private final Clock clock;
    private final Map<UUID, Object> sessions = new ConcurrentHashMap<>();

    public ProxyPresenceReporter(String nodeId, ProxyPlayers players, AgoraLink link, Clock clock) {
        this.nodeId = nodeId;
        this.players = players;
        this.link = link;
        this.clock = clock;
    }

    /** Completes with true if Agora accepted the login; false on rejection, error or timeout. Never throws. */
    public CompletableFuture<Boolean> login(Object session, UUID uuid, String name, String locale) {
        ClaimPresenceRequest request = ClaimPresenceRequest.newBuilder().setEntry(PresenceEntry.newBuilder()
                .setPlayer(uuid.toString())
                .setName(name)
                .setServer("")
                .setProxy(nodeId)
                .setLocale(locale == null ? "" : locale)
                .setOnlineSinceMillis(clock.millis())).build();
        CompletableFuture<Boolean> result;
        try {
            result = link.claimPresence(request)
                    .orTimeout(CLAIM_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .handle((response, error) -> error == null && response.getAccepted());
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(false);
        }
        return result.thenApply(accepted -> {
            if (accepted) sessions.put(uuid, session);
            return accepted;
        });
    }

    public void switched(Object session, UUID uuid, String server) {
        if (sessions.get(uuid) != session) return;
        link.send(NodeMessage.newBuilder().setPlayerSwitched(
                PlayerSwitched.newBuilder().setPlayer(uuid.toString()).setServer(server)).build());
    }

    public void left(Object session, UUID uuid) {
        if (!sessions.remove(uuid, session)) return;
        link.send(NodeMessage.newBuilder().setPlayerLeft(PlayerLeft.newBuilder().setPlayer(uuid.toString())).build());
    }

    /** Full state: all players of this proxy with their current server. */
    public NodeStateSupplier state() {
        return new NodeStateSupplier() {
            @Override
            public Hello hello() {
                return Hello.newBuilder().setNodeId(nodeId).setKind(NodeKind.VELOCITY).addAllPlayers(entries()).build();
            }

            @Override
            public Heartbeat heartbeat() {
                return Heartbeat.newBuilder().addAllPlayers(entries()).build();
            }
        };
    }

    private List<PresenceEntry> entries() {
        return players.online().stream().map(p -> PresenceEntry.newBuilder()
                .setPlayer(p.uuid().toString())
                .setName(p.name())
                .setServer(p.currentServer().orElse(""))
                .setProxy(nodeId)
                .setLocale(p.locale())
                .setOnlineSinceMillis(0)
                .build()).toList();
    }
}
