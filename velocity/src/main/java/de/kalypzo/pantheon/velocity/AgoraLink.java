package de.kalypzo.pantheon.velocity;

import de.kalypzo.agora.proto.v1.ClaimPresenceRequest;
import de.kalypzo.agora.proto.v1.ClaimPresenceResponse;
import de.kalypzo.agora.proto.v1.NodeMessage;

import java.util.concurrent.CompletableFuture;

/** The part of the Agora connection the proxy uses. */
public interface AgoraLink {

    /** Unary call with a 5 s deadline. */
    CompletableFuture<ClaimPresenceResponse> claimPresence(ClaimPresenceRequest request);

    /** Sends on the node stream; false if not connected. */
    boolean send(NodeMessage message);
}
