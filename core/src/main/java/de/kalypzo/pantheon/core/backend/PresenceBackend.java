package de.kalypzo.pantheon.core.backend;

import de.kalypzo.pantheon.data.Subscription;
import de.kalypzo.pantheon.world.NetworkLocation;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Presence port (agora.md §2, §4). */
public interface PresenceBackend {

    /** All online players, from memory. */
    Collection<PresenceEntry> snapshot();

    Subscription onPresenceChanged(Consumer<PresenceChange> listener);

    /** Asks the player's server; offline → {@code ExpectedOnlinePlayerException(NETWORK)}. */
    CompletableFuture<NetworkLocation> location(UUID player);

    /** Answers location requests for players on this server. */
    void answerLocations(LocationResolver resolver);
}
