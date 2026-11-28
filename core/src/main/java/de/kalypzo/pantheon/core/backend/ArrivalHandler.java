package de.kalypzo.pantheon.core.backend;

import de.kalypzo.pantheon.world.NetworkLocation;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** Answers the arrival steps of a teleport to this server ({@code PrepareArrival}/{@code CancelArrival}). */
public interface ArrivalHandler {

    /** Prepares the arrival of {@code player}; {@code location} {@code null} = connect only (normal spawn). */
    CompletableFuture<ArrivalStatus> prepare(String requestId, UUID player, @Nullable NetworkLocation location);

    /** The teleport {@code requestId} ended without arrival; forget what {@link #prepare} noted for it. */
    void cancel(String requestId, UUID player);
}
