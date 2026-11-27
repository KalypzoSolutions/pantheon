package de.kalypzo.pantheon.velocity;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** Interface over Velocity so the proxy logic is testable without a proxy. */
public interface ProxyPlayers {

    Collection<ProxyPlayer> online();

    Optional<ProxyPlayer> find(UUID uuid);
}
