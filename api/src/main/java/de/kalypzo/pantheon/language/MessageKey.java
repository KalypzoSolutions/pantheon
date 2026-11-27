package de.kalypzo.pantheon.language;

/** Key of a translatable message. Plugins usually implement it as an enum. */
public interface MessageKey {

    String key();

    /** The type this message is sent with by default. */
    default MessageType type() {
        return MessageType.DEFAULT;
    }

    /** A key that is only known at runtime. */
    static MessageKey of(String key) {
        java.util.Objects.requireNonNull(key, "key");
        return () -> key;
    }
}
