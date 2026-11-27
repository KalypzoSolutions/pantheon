package de.kalypzo.pantheon.language;

/** Standard messages supplied by Pantheon. A plugin's own file wins for the same key. */
public enum CommonMessage implements MessageKey {
    NO_PERMISSION("pantheon.no-permission", MessageType.DENY),
    PLAYER_NOT_FOUND("pantheon.player-not-found", MessageType.DENY),
    PLAYERS_ONLY("pantheon.players-only", MessageType.DENY),
    DATA_UNAVAILABLE("pantheon.data-unavailable", MessageType.ERROR),
    CLICK_TO_CONFIRM("pantheon.click-to-confirm");

    private final String key;
    private final MessageType type;

    CommonMessage(String key) {
        this(key, MessageType.DEFAULT);
    }

    CommonMessage(String key, MessageType type) {
        this.key = key;
        this.type = type;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public MessageType type() {
        return type;
    }
}
