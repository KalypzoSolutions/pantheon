package de.kalypzo.pantheon.paper;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link LocalPanPlayerImpl}s of the players on this server: added at {@code PlayerJoinEvent} ({@code LOWEST}),
 * removed at {@code PlayerQuitEvent} ({@code MONITOR}). Thread-safe.
 */
public final class LocalPlayers {

    private final Map<UUID, LocalPanPlayerImpl> players = new ConcurrentHashMap<>();

    public Optional<LocalPanPlayerImpl> get(UUID uuid) {
        return Optional.ofNullable(players.get(Objects.requireNonNull(uuid, "uuid")));
    }

    public Collection<LocalPanPlayerImpl> all() {
        return List.copyOf(players.values());
    }

    void add(LocalPanPlayerImpl player) {
        LocalPanPlayerImpl old = players.put(player.uniqueId(), player);
        if (old != null && old != player) old.end(); // a stale handle of an earlier session
    }

    /** Removes the handle of {@code uuid}; empty if there is none. */
    Optional<LocalPanPlayerImpl> remove(UUID uuid) {
        return Optional.ofNullable(players.remove(uuid));
    }
}
