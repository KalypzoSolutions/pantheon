package de.kalypzo.pantheon.velocity;

import de.kalypzo.agora.proto.v1.KickPlayer;
import de.kalypzo.agora.proto.v1.KickResult;
import de.kalypzo.agora.proto.v1.NodeMessage;

import java.util.Optional;
import java.util.UUID;

/** Answers Agora's double-login kick; Agora waits at most 3 s for the result. */
public final class ProxyKickHandler {

    private final ProxyPlayers players;
    private final AgoraLink link;

    public ProxyKickHandler(ProxyPlayers players, AgoraLink link) {
        this.players = players;
        this.link = link;
    }

    public void handle(KickPlayer kick) {
        boolean wasOnline = false;
        try {
            Optional<ProxyPlayer> player = players.find(UUID.fromString(kick.getPlayer()));
            if (player.isPresent()) {
                player.get().disconnect(ProxyTexts.LOGGED_IN_ELSEWHERE);
                wasOnline = true;
            }
        } catch (IllegalArgumentException ignored) {
            // malformed UUID: not online here
        }
        link.send(NodeMessage.newBuilder().setKickResult(
                KickResult.newBuilder().setRequestId(kick.getRequestId()).setWasOnline(wasOnline)).build());
    }
}
