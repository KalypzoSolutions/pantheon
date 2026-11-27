package de.kalypzo.pantheon.player;

import java.util.UUID;

public final class ExpectedOnlinePlayerException extends RuntimeException {

    private final UUID uuid;
    private final Scope scope;

    public ExpectedOnlinePlayerException(UUID uuid, Scope scope) {
        super("Player with UUID " + uuid + " is not online in scope " + scope);
        this.uuid = uuid;
        this.scope = scope;
    }

    public UUID uuid() {
        return uuid;
    }

    public Scope scope() {
        return scope;
    }

    public enum Scope {
        LOCAL,
        NETWORK
    }
}
