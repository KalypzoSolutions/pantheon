package de.kalypzo.pantheon.core.audit;

import de.kalypzo.pantheon.core.data.UuidV7;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The active trace per player on this node (audit_log.md §4). Thread-safe. A new {@link #begin} replaces the old
 * trace without logging; actions in the namespace {@code pantheon} never get an automatic trace.
 */
public final class TraceRegistry {

    private final ConcurrentHashMap<UUID, UUID> active = new ConcurrentHashMap<>();
    private final Clock clock;
    private final UuidV7.Generator ids = new UuidV7.Generator();

    public TraceRegistry() {
        this(Clock.systemUTC());
    }

    public TraceRegistry(Clock clock) {
        this.clock = clock;
    }

    /** Starts a new trace for the player and ends the previous one without an entry. */
    public UUID begin(UUID player) {
        UUID trace = ids.next(clock);
        active.put(player, trace);
        return trace;
    }

    /** Ends the trace, only if it is still the active one. */
    public void end(UUID player, UUID trace) {
        active.remove(player, trace);
    }

    public Optional<UUID> active(UUID player) {
        return Optional.ofNullable(active.get(player));
    }

    public void endAll(UUID player) {
        active.remove(player);
    }

    /** The explicit trace if given, else the active one; {@code pantheon:*} only gets an explicit trace. */
    public @Nullable UUID traceFor(UUID player, String action, @Nullable UUID explicit) {
        if (explicit != null) {
            return explicit;
        }
        if (action.startsWith("pantheon:")) {
            return null;
        }
        return active.get(player);
    }
}
