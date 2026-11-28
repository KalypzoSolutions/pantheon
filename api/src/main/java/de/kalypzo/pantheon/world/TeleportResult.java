package de.kalypzo.pantheon.world;

import de.kalypzo.pantheon.language.CommonMessage;
import de.kalypzo.pantheon.language.MessageKey;
import java.util.Optional;

/**
 * Outcome of a network-wide teleport or connect. {@link #message()} gives a ready message key for failures the
 * player should be told about; it is empty for {@link #OK} and {@link #SUPERSEDED} (a newer teleport took over).
 */
public enum TeleportResult {
    OK,
    NOT_ONLINE(CommonMessage.TELEPORT_NOT_ONLINE),
    SERVER_UNAVAILABLE(CommonMessage.TELEPORT_SERVER_UNAVAILABLE),
    WORLD_NOT_FOUND(CommonMessage.TELEPORT_WORLD_NOT_FOUND),
    CONNECT_FAILED(CommonMessage.TELEPORT_CONNECT_FAILED),
    SUPERSEDED,
    TIMEOUT(CommonMessage.TELEPORT_TIMEOUT),
    NETWORK_UNAVAILABLE(CommonMessage.TELEPORT_NETWORK_UNAVAILABLE);

    private final MessageKey message;

    TeleportResult() {
        this(null);
    }

    TeleportResult(MessageKey message) {
        this.message = message;
    }

    public boolean ok() {
        return this == OK;
    }

    public Optional<MessageKey> message() {
        return Optional.ofNullable(message);
    }
}
