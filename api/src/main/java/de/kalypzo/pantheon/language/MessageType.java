package de.kalypzo.pantheon.language;

import java.util.Objects;
import java.util.regex.Pattern;

/** Gives a message context (colour, prefix, sound). A value type, equal by name. */
public final class MessageType {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]+");

    public static final MessageType PLAIN = new MessageType("plain");
    public static final MessageType PROMPT = new MessageType("prompt");
    public static final MessageType DEFAULT = new MessageType("default");
    public static final MessageType SUCCESS = new MessageType("success");
    public static final MessageType DENY = new MessageType("deny");
    public static final MessageType ERROR = new MessageType("error");

    private final String name;

    private MessageType(String name) {
        this.name = name;
    }

    /** @throws IllegalArgumentException if the name is not {@code [a-z0-9_-]+} */
    public static MessageType of(String name) {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid message type name '" + name + "', expected [a-z0-9_-]+");
        }
        return new MessageType(name);
    }

    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MessageType other && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
