package de.kalypzo.pantheon.player;

import java.util.regex.Pattern;

/** Key for per-session data of a player. Identity is per instance, not per name. */
public final class SessionData<T> {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]+");

    private final String name;

    private SessionData(String name) {
        this.name = name;
    }

    public static <T> SessionData<T> of(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Ungültiger Name für SessionData: \"" + name + "\" (erlaubt: [a-z0-9_-]+)");
        }
        return new SessionData<>(name);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "SessionData[" + name + "]";
    }
}
