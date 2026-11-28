package de.kalypzo.pantheon.core.backend;

import de.kalypzo.pantheon.data.Subscription;
import de.kalypzo.pantheon.world.NetworkLocation;
import de.kalypzo.pantheon.world.TeleportResult;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Presence port (agora.md §2, §4). */
public interface PresenceBackend {

    /** All online players, from memory. */
    Collection<PresenceEntry> snapshot();

    Subscription onPresenceChanged(Consumer<PresenceChange> listener);

    /** Asks the player's server; offline → {@code ExpectedOnlinePlayerException(NETWORK)}. */
    CompletableFuture<NetworkLocation> location(UUID player);

    /** Answers location requests for players on this server. */
    void answerLocations(LocationResolver resolver);

    /**
     * Moves {@code player} to {@code server}, to {@code location} there, or only connects it when {@code location}
     * is {@code null}. Completes when the player arrived or the teleport failed; never exceptionally.
     */
    CompletableFuture<TeleportResult> teleport(UUID player, String server, @Nullable NetworkLocation location);

    /** Answers the arrival steps of teleports to this server. */
    void answerArrivals(ArrivalHandler handler);

    /** Reports the end of a prepared arrival: {@code true} = arrived, {@code false} = failed on this server. */
    void reportArrival(String requestId, boolean arrived);
}
