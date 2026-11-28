package de.kalypzo.pantheon.velocity;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import de.kalypzo.agora.proto.v1.ConnectPlayer;
import de.kalypzo.agora.proto.v1.ConnectResult;
import de.kalypzo.agora.proto.v1.ConnectStatus;
import de.kalypzo.agora.proto.v1.NodeMessage;

import java.util.Optional;
import java.util.UUID;

/** Answers Agora's connect request; a {@link ConnectResult} is always sent, never an exception. */
public final class ProxyConnectHandler {

    private final ProxyPlayers players;
    private final AgoraLink link;

    public ProxyConnectHandler(ProxyPlayers players, AgoraLink link) {
        this.players = players;
        this.link = link;
    }

    public void handle(ConnectPlayer connect) {
        Optional<ProxyPlayer> player;
        try {
            player = players.find(UUID.fromString(connect.getPlayer()));
        } catch (IllegalArgumentException e) {
            player = Optional.empty();
        }
        if (player.isEmpty()) {
            reply(connect, ConnectStatus.PLAYER_NOT_FOUND);
            return;
        }
        try {
            player.get().connect(connect.getServer())
                    .whenComplete((status, error) -> reply(connect, error != null || status == null
                            ? ConnectStatus.FAILED : status));
        } catch (RuntimeException e) {
            reply(connect, ConnectStatus.FAILED);
        }
    }

    private void reply(ConnectPlayer connect, ConnectStatus status) {
        link.send(NodeMessage.newBuilder().setConnectResult(
                ConnectResult.newBuilder().setRequestId(connect.getRequestId()).setStatus(status)).build());
    }

    static ConnectStatus fromVelocity(ConnectionRequestBuilder.Status status) {
        return switch (status) {
            case SUCCESS -> ConnectStatus.CONNECTED;
            case ALREADY_CONNECTED -> ConnectStatus.ALREADY_CONNECTED;
            case CONNECTION_IN_PROGRESS, CONNECTION_CANCELLED, SERVER_DISCONNECTED -> ConnectStatus.FAILED;
        };
    }
}
