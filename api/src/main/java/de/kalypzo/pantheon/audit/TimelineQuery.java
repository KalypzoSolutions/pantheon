package de.kalypzo.pantheon.audit;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

/** Reads a player's timeline, newest first. A viewer is mandatory. */
public interface TimelineQuery {

    /** A player sender gives UUID and name, otherwise the nil UUID with the sender's name and via {@code command}. */
    TimelineQuery viewer(CommandSender sender);

    /** {@code player} is null for "no player" (nil UUID). */
    TimelineQuery viewer(@Nullable UUID player, String name, String via);

    /** {@code to} is exclusive. */
    TimelineQuery between(Instant from, Instant to);

    TimelineQuery actions(AuditAction... actions);

    /** All actions of the namespace, i.e. {@code ns:*}. */
    TimelineQuery namespace(AuditNamespace namespace);

    TimelineQuery trace(UUID trace);

    /** Default 100, at most 500. */
    TimelineQuery limit(int limit);

    TimelineQuery after(UUID cursor);

    /** Fails with {@link IllegalStateException} ("viewer fehlt") if no viewer is set. */
    CompletableFuture<TimelinePage> fetch();
}
