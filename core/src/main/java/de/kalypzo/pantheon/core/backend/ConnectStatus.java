package de.kalypzo.pantheon.core.backend;

/** What the proxy reports for {@code ConnectPlayer}. */
public enum ConnectStatus {
    CONNECTED,
    ALREADY_CONNECTED,
    FAILED,
    PLAYER_NOT_FOUND
}
