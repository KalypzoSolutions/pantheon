package de.kalypzo.pantheon.core.backend.memory;

import java.time.Duration;
import java.util.Objects;

/** The time limits of a teleport in the {@link InMemoryNetwork}, the same as Agora's. */
public record TeleportTimeouts(Duration prepare, Duration connect, Duration arrival, Duration total) {

    public static final TeleportTimeouts DEFAULT = new TeleportTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(10),
            Duration.ofSeconds(25), Duration.ofSeconds(38));

    public TeleportTimeouts {
        Objects.requireNonNull(prepare, "prepare");
        Objects.requireNonNull(connect, "connect");
        Objects.requireNonNull(arrival, "arrival");
        Objects.requireNonNull(total, "total");
    }
}
