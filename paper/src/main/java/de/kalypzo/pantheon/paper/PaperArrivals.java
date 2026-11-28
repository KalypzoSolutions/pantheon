package de.kalypzo.pantheon.paper;

import de.kalypzo.pantheon.core.backend.ArrivalHandler;
import de.kalypzo.pantheon.core.backend.ArrivalStatus;
import de.kalypzo.pantheon.world.NetworkLocation;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.Nullable;

/**
 * Arrivals of network teleports on this server (spec "Ablauf", steps 2 and 4): {@link #prepare} notes where an
 * arriving player spawns, the spawn event sets it, and the join reports the arrival (with a fallback teleport if the
 * spawn event ran before the note).
 */
final class PaperArrivals implements ArrivalHandler, Listener {

    /** How long a noted arrival stays valid, from {@code PrepareArrival}. */
    static final Duration TTL = Duration.ofSeconds(35);

    /** A noted arrival; {@code location} {@code null} = connect only, {@code applied} = set by the spawn event. */
    record Pending(String requestId, @Nullable Location location, Instant expires, boolean applied) {
    }

    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Server server;
    private final Predicate<UUID> joined;
    private final BiConsumer<String, Boolean> report;
    private final Executor mainThread;
    private final Clock clock;
    private final Logger log;

    PaperArrivals(Server server, Predicate<UUID> joined, BiConsumer<String, Boolean> report, Executor mainThread,
                  Clock clock, Logger log) {
        this.server = Objects.requireNonNull(server, "server");
        this.joined = Objects.requireNonNull(joined, "joined");
        this.report = Objects.requireNonNull(report, "report");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Runs on the main thread, so the check "player local?" cannot interleave with a join. */
    @Override
    public CompletableFuture<ArrivalStatus> prepare(String requestId, UUID player,
                                                    @Nullable NetworkLocation location) {
        CompletableFuture<ArrivalStatus> answer = new CompletableFuture<>();
        try {
            mainThread.execute(() -> {
                try {
                    prepareNow(requestId, player, location, answer);
                } catch (RuntimeException e) {
                    answer.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) { // the executor rejected the task (e.g. shutdown)
            answer.completeExceptionally(e);
        }
        return answer;
    }

    private void prepareNow(String requestId, UUID uuid, @Nullable NetworkLocation location,
                            CompletableFuture<ArrivalStatus> answer) {
        Location target = null;
        if (location != null) {
            World world = server.getWorld(location.worldName());
            if (world == null) {
                answer.complete(ArrivalStatus.WORLD_NOT_FOUND);
                return;
            }
            target = new Location(world, location.x(), location.y(), location.z(), location.yaw(), location.pitch());
        }
        if (server.getPlayer(uuid) != null && joined.test(uuid)) {
            answer.complete(ArrivalStatus.ALREADY_HERE); // the answer first, the teleport in a task of its own
            Location to = target;
            try {
                mainThread.execute(() -> {
                    Player p = server.getPlayer(uuid);
                    if (p == null) report(requestId, false); // quit in between
                    else arrive(requestId, p, to);
                });
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Arrival " + requestId + " of " + uuid + " could not be scheduled", e);
                report(requestId, false);
            }
            return;
        }
        pending.put(uuid, new Pending(requestId, target, clock.instant().plus(TTL), false));
        answer.complete(ArrivalStatus.READY);
    }

    @Override
    public void cancel(String requestId, UUID player) {
        pending.computeIfPresent(player, (k, p) -> p.requestId().equals(requestId) ? null : p);
    }

    /** The noted spawn location of {@code player}, marked as applied; empty if none, connect only, or expired. */
    Optional<Location> spawnLocation(UUID player) {
        Instant now = clock.instant();
        Location[] found = new Location[1];
        // atomic against a concurrent prepare on the main thread (the spawn event is async)
        pending.computeIfPresent(player, (k, p) -> {
            if (now.isAfter(p.expires())) return null;
            if (p.location() == null) return p;
            found[0] = p.location().clone();
            return new Pending(p.requestId(), p.location(), p.expires(), true);
        });
        return Optional.ofNullable(found[0]);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent e) {
        spawnLocation(e.getConnection().getProfile().getId()).ifPresent(e::setSpawnLocation);
    }

    /** After {@code PlayerLifecycleListener.onJoin} (LOWEST), which adds the handle or kicks without a profile. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        Pending p = pending.remove(uuid);
        if (p == null || clock.instant().isAfter(p.expires())) return;
        if (!joined.test(uuid)) report(p.requestId(), false); // kicked without a profile
        else if (p.applied()) report(p.requestId(), true);
        else arrive(p.requestId(), player, p.location()); // the spawn event ran before the note: fallback
    }

    /** Teleports {@code player} to {@code to} (nothing for connect only) and reports the outcome; never throws. */
    private void arrive(String requestId, Player player, @Nullable Location to) {
        if (to == null) {
            report(requestId, true);
            return;
        }
        try {
            player.teleportAsync(to).whenComplete((ok, error) -> {
                if (error != null) log.log(Level.WARNING, "Arrival teleport of " + player.getName() + " failed", error);
                report(requestId, error == null && Boolean.TRUE.equals(ok));
            });
        } catch (RuntimeException e) { // Bukkit rejects NaN and infinite coordinates with an exception
            log.log(Level.WARNING, "Arrival teleport of " + player.getName() + " failed", e);
            report(requestId, false);
        }
    }

    private void report(String requestId, boolean arrived) {
        try {
            report.accept(requestId, arrived);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Arrival report " + requestId + " failed", e);
        }
    }
}
