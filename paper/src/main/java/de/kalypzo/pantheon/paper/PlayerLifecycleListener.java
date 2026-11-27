package de.kalypzo.pantheon.paper;

import de.kalypzo.pantheon.core.audit.TraceRegistry;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.player.LoginCoordinator;
import de.kalypzo.pantheon.core.player.LoginOutcome;
import de.kalypzo.pantheon.core.player.PlayerDataEngine;
import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.data.DataUnavailableException;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.PanPlayer;
import de.kalypzo.pantheon.player.PanPlayerFactory;
import de.kalypzo.pantheon.player.PanPlayerLoadEvent;
import de.kalypzo.pantheon.world.NetworkLocation;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;

/**
 * The life cycle of players on this server (player.md §7, §9, E25), on top of the {@link LoginCoordinator}:
 * <ul>
 *   <li>{@code AsyncPlayerPreLoginEvent} ({@code NORMAL}, only if allowed so far): loads the data, waiting at most
 *       the login timeout + 1 s (R29). Denied: {@code disallow(KICK_OTHER, DATA_UNAVAILABLE)} in the default
 *       language, never empty data. Allowed: {@link PanPlayerLoadEvent} (async). A later listener refusing the
 *       pre-login ({@code MONITOR}) or the {@code PlayerLoginEvent} ({@code MONITOR}) releases at once; a client that
 *       never joins is released after 30 s by the coordinator.
 *   <li>{@code PlayerJoinEvent} ({@code LOWEST}): the {@link LocalPanPlayerImpl} goes into {@link LocalPlayers}, the
 *       coordinator stops the join timer. Without a profile (must not happen): kick with {@code DATA_UNAVAILABLE} and
 *       an ERROR.
 *   <li>{@code PlayerQuitEvent} ({@code MONITOR}, after every change of the other plugins): {@link #quit} runs its
 *       steps in order (traces, session, handle, presence, release). The player's audit traces end first (E41):
 *       entries of plugins up to {@code HIGHEST} still carry them.
 * </ul>
 * In memory mode it also reports join and quit to the {@link InMemoryNetwork} (R12: {@code join(server = null)},
 * then {@code switchServer(nodeId)}). It answers location requests for players on this server on the main thread.
 */
public final class PlayerLifecycleListener implements Listener {

    private final LoginCoordinator coordinator;
    private final PlayerDataEngine engine;
    private final LocalPlayers players;
    private final String nodeId;
    private final Optional<InMemoryNetwork> network;
    private final Executor mainThread;
    private final PluginManager events;
    private final Supplier<Component> dataUnavailable;
    private final Logger log;
    private final BiFunction<LocalPanPlayerImpl, Player, PanPlayer> panPlayer;
    private final TraceRegistry traces;

    public PlayerLifecycleListener(LoginCoordinator coordinator, PlayerDataEngine engine, LocalPlayers players,
                                   String nodeId, Optional<InMemoryNetwork> network, Executor mainThread,
                                   PluginManager events, Supplier<Component> dataUnavailable, Logger log,
                                   BiFunction<LocalPanPlayerImpl, Player, PanPlayer> panPlayer,
                                   TraceRegistry traces) {
        this.traces = Objects.requireNonNull(traces, "traces");
        this.panPlayer = Objects.requireNonNull(panPlayer, "panPlayer");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.players = Objects.requireNonNull(players, "players");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.network = Objects.requireNonNull(network, "network");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.events = Objects.requireNonNull(events, "events");
        this.dataUnavailable = Objects.requireNonNull(dataUnavailable, "dataUnavailable");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * The handles of the players on this server, for the runtime (hooks, tests). The network-wide view is the
     * {@link PaperPlayerService} of each plugin.
     */
    public PanPlayerFactory localFactory() {
        return new PanPlayerFactory() {
            @Override
            public LocalPanPlayer local(Player player) {
                // not joined (yet) through this listener: a handle of an ended session, never null
                return players.get(player.getUniqueId()).orElseGet(() -> ended(player));
            }

            @Override
            public Optional<PanPlayer> online(UUID uuid) {
                throw networkView();
            }

            @Override
            public Optional<PanPlayer> online(String name) {
                throw networkView();
            }

            @Override
            public Collection<PanPlayer> online() {
                throw networkView();
            }

            @Override
            public Collection<LocalPanPlayer> onlineLocal() {
                return List.copyOf(players.all());
            }

            private UnsupportedOperationException networkView() {
                return new UnsupportedOperationException("Players of the whole network: Pantheon.players()");
            }
        };
    }

    /** Answers location requests for players on this server: read on the main thread. */
    public CompletableFuture<Optional<NetworkLocation>> resolveLocation(UUID uuid) {
        Optional<LocalPanPlayerImpl> local = players.get(uuid);
        if (local.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
        Player bukkit;
        try {
            bukkit = local.get().bukkit();
        } catch (RuntimeException e) { // quit in the meantime
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return locate(bukkit).thenApply(Optional::of);
    }

    // ---------- login ----------

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        LoginOutcome outcome = awaitPreLogin(uuid, name);
        switch (outcome) {
            case LoginOutcome.Denied denied -> {
                log.warning("Login of " + name + " (" + uuid + ") refused, data unavailable: " + denied.cause());
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, dataUnavailable.get());
            }
            case LoginOutcome.Allowed ok -> {
                events.callEvent(new PanPlayerLoadEvent(uuid, name, ok.doc())); // we are on the async login thread
            }
        }
    }

    /**
     * Another listener refused the pre-login after Pantheon loaded the data: release at once. If Pantheon itself
     * refused, nothing is held and this is a no-op.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLoginResult(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            coordinator.loginAborted(event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings("deprecation") // the login event is the one that tells a refused login
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() == PlayerLoginEvent.Result.ALLOWED) return;
        coordinator.loginAborted(event.getPlayer().getUniqueId());
    }

    private LoginOutcome awaitPreLogin(UUID uuid, String name) {
        Duration wait = coordinator.loginTimeout().plusSeconds(1); // R29: never without a deadline
        CompletableFuture<LoginOutcome> future = coordinator.preLogin(uuid, name);
        try {
            return future.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return denied(uuid, future, new DataUnavailableException("login of " + uuid + " interrupted"));
        } catch (TimeoutException e) {
            return denied(uuid, future, new DataUnavailableException("login of " + uuid + " not answered within "
                    + wait.toMillis() + " ms"));
        } catch (ExecutionException e) { // preLogin never fails, but be safe
            return denied(uuid, future, e.getCause() == null ? e : e.getCause());
        }
    }

    /** Given up waiting: a load that still completes is released (the player never joins). */
    private LoginOutcome denied(UUID uuid, CompletableFuture<LoginOutcome> future, Throwable cause) {
        future.thenAccept(late -> {
            if (late instanceof LoginOutcome.Allowed) coordinator.loginAborted(uuid);
        });
        return new LoginOutcome.Denied(cause);
    }

    // ---------- join ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Optional<Doc> profile = engine.profile(uuid);
        if (profile.isEmpty()) { // the pre-login guarantees the data; this must not happen
            log.severe("Player " + player.getName() + " (" + uuid + ") joined without loaded data; kicking");
            player.kick(dataUnavailable.get());
            return;
        }
        players.add(handle(player, profile.get()));
        coordinator.joined(uuid);
        network.ifPresent(n -> { // R12: the proxy's join, then the switch to this node
            n.join(new PresenceEntry(uuid, player.getName(), null, nodeId, player.locale(), Instant.now()));
            n.switchServer(uuid, nodeId);
        });
    }

    // ---------- quit ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        quit(event.getPlayer().getUniqueId());
    }

    /**
     * The quit steps, in order (player.md §7). Every change up to {@code HIGHEST} is already queued in the writer and
     * survives the release.
     */
    private void quit(UUID uuid) {
        traces.endAll(uuid); // E41: at MONITOR, after every plugin entry of the quit
        players.remove(uuid).ifPresent(LocalPanPlayerImpl::end); // session data dropped, the handle ends
        network.ifPresent(n -> n.leave(uuid));
        try {
            coordinator.quit(uuid); // no longer holder, the profile leaves memory, running writes finish
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Releasing player " + uuid + " failed", e);
        }
    }

    // ---------- helpers ----------

    private LocalPanPlayerImpl handle(Player player, Doc data) {
        return new LocalPanPlayerImpl(player, data, mainThread, nodeId, self -> panPlayer.apply(self, player));
    }

    private LocalPanPlayerImpl ended(Player player) {
        return LocalPanPlayerImpl.ended(player, mainThread, nodeId, self -> panPlayer.apply(self, player));
    }

    private CompletableFuture<NetworkLocation> locate(Player player) {
        return CompletableFuture.supplyAsync(() -> {
            Location l = player.getLocation();
            return new NetworkLocation(nodeId, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(),
                    l.getPitch());
        }, mainThread);
    }
}
