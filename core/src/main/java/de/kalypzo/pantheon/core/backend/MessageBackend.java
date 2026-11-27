package de.kalypzo.pantheon.core.backend;

import java.util.UUID;

/** Message port (agora.md §2, §6). */
public interface MessageBackend {

    /** Fully rendered, fire-and-forget; dropped if the player is offline. */
    void deliver(UUID player, Delivery delivery);
}
