package de.kalypzo.pantheon.agora;

import de.kalypzo.agora.proto.v1.ServiceMessage;
import de.kalypzo.agora.proto.v1.Welcome;
import io.grpc.Status;

/** Callbacks of an {@link AgoraConnection}. They run on gRPC/scheduler threads and must not block. */
public interface ConnectionListener {

    /**
     * The connection is usable (R49): the {@code BarrierAck} for the barrier sent right after {@code Hello} arrived,
     * on every (re)connect. Never called for a stream Agora rejects (UNAUTHENTICATED). Called under the connection's
     * lock, before any {@link #onDisconnected}/{@link #onFatal} of the same stream; must return quickly.
     */
    default void onConnected() {}

    /** Answer to {@code Hello}; carries the full presence. */
    void onWelcome(Welcome welcome);

    /** Every other message from the service. */
    void onMessage(ServiceMessage message);

    /** The stream ended; the connection reconnects with backoff. */
    void onDisconnected(Status status);

    /** UNAUTHENTICATED or PERMISSION_DENIED: no further reconnect. */
    void onFatal(Status status);
}
