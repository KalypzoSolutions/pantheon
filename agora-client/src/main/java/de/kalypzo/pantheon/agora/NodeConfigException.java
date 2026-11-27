package de.kalypzo.pantheon.agora;

/** Thrown when the node configuration is incomplete. The message names the variable, never a value. */
public final class NodeConfigException extends Exception {

    public NodeConfigException(String message) {
        super(message);
    }
}
