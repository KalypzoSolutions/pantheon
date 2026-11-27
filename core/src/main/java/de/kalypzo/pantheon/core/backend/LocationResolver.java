package de.kalypzo.pantheon.core.backend;

import de.kalypzo.pantheon.world.NetworkLocation;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Resolves the location of a player on this server; empty if the player is not here. */
@FunctionalInterface
public interface LocationResolver {

    CompletableFuture<Optional<NetworkLocation>> resolve(UUID player);
}
