package de.kalypzo.pantheon.paper;

import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.player.ExpectedOnlinePlayerException;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.PanPlayer;
import de.kalypzo.pantheon.player.SessionData;
import de.kalypzo.pantheon.world.NetworkLocation;
import de.kalypzo.pantheon.world.TeleportResult;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * A player on this server for one session, from {@code PlayerJoinEvent} to {@code PlayerQuitEvent}. The synchronous
 * side ({@link #bukkit()}, {@link #data()}, {@link #session}, {@link #hasPermission}) throws
 * {@link ExpectedOnlinePlayerException} ({@code LOCAL}) after the quit; session data lives in a
 * {@link ConcurrentHashMap} and is dropped at the quit. Every {@link PanPlayer} method is delegated to the network
 * view of the player (R10: the {@code NetworkPanPlayer}), except the audience and the position, which a player
 * on this server reads from Bukkit itself (the network view uses this handle as its audience, R9), so a handle kept past the quit still works as a
 * {@code PanPlayer}. Thread-safe.
 */
public final class LocalPanPlayerImpl implements LocalPanPlayer {

    private static final Logger LOG = Logger.getLogger(LocalPanPlayerImpl.class.getName());

    private final UUID uuid;
    private final Player bukkit;
    private final @Nullable Doc data; // null only for an ended handle
    private final Map<SessionData<?>, Object> session = new ConcurrentHashMap<>();
    private final PanPlayer network;
    private final Executor mainThread;
    private final String nodeId;
    private volatile boolean ended;

    /**
     * @param network builds the network view of this player (its PanPlayer methods), given this handle
     */
    public LocalPanPlayerImpl(Player bukkit, Doc data, Executor mainThread, String nodeId,
                              Function<? super LocalPanPlayerImpl, ? extends PanPlayer> network) {
        this(bukkit, Objects.requireNonNull(data, "data"), mainThread, nodeId, network, false);
    }

    private LocalPanPlayerImpl(Player bukkit, @Nullable Doc data, Executor mainThread, String nodeId,
                               Function<? super LocalPanPlayerImpl, ? extends PanPlayer> network, boolean ended) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.bukkit = Objects.requireNonNull(bukkit, "bukkit");
        this.uuid = bukkit.getUniqueId();
        this.data = data;
        this.ended = ended;
        this.network = Objects.requireNonNull(network.apply(this), "network");
    }

    /** A handle whose session is over (or never started here): only its PanPlayer side works. */
    static LocalPanPlayerImpl ended(Player bukkit, Executor mainThread, String nodeId,
                                    Function<? super LocalPanPlayerImpl, ? extends PanPlayer> network) {
        return new LocalPanPlayerImpl(bukkit, null, mainThread, nodeId, network, true);
    }

    /** The session is over: session data is dropped, the synchronous side throws from now on. */
    void end() {
        ended = true;
        session.clear();
    }

    /** Whether the player is still on this server in this session. */
    public boolean isPresent() {
        return !ended;
    }

    // ---------- LocalPanPlayer ----------

    @Override
    public Player bukkit() {
        check();
        return bukkit;
    }

    @Override
    public boolean hasPermission(String permission) {
        Objects.requireNonNull(permission, "permission");
        check();
        return bukkit.hasPermission(permission);
    }

    @Override
    public Doc data() {
        check();
        return Objects.requireNonNull(data, "data"); // only an ended handle has none, and check() threw
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> session(SessionData<T> key) {
        Objects.requireNonNull(key, "key");
        check();
        return Optional.ofNullable((T) session.get(key));
    }

    @Override
    public <T> void session(SessionData<T> key, @Nullable T value) {
        Objects.requireNonNull(key, "key");
        check();
        if (value == null) {
            session.remove(key);
            return;
        }
        session.put(key, value);
        if (ended) { // the quit cleared the map in the meantime
            session.remove(key);
            check();
        }
    }

    private void check() {
        if (ended) throw new ExpectedOnlinePlayerException(uuid, ExpectedOnlinePlayerException.Scope.LOCAL);
    }

    // ---------- PanPlayer: the network view (R10) ----------

    /** The network view's log: the node's one buffer, also after the quit (audit §4). */
    @Override
    public de.kalypzo.pantheon.audit.AuditLog audit() {
        return network.audit();
    }

    @Override
    public UUID uniqueId() {
        return uuid;
    }

    @Override
    public String name() {
        return network.name();
    }

    @Override
    public Locale lang() {
        return network.lang();
    }

    @Override
    public boolean isOnline() {
        return network.isOnline();
    }

    @Override
    public boolean isLocal() {
        return network.isLocal();
    }

    @Override
    public Optional<String> server() {
        return network.server();
    }

    @Override
    public Optional<LocalPanPlayer> local() {
        return network.local();
    }

    @Override
    public CompletableFuture<NetworkLocation> location() {
        if (ended) return network.location();
        return CompletableFuture.supplyAsync(() -> { // Bukkit is read on the main thread
            Location l = bukkit.getLocation();
            return new NetworkLocation(nodeId, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(),
                    l.getPitch());
        }, mainThread);
    }

    @Override
    public CompletableFuture<TeleportResult> teleport(NetworkLocation location) {
        Objects.requireNonNull(location, "location");
        if (ended || !location.serverName().equals(nodeId)) return network.teleport(location);
        // the Bukkit calls run on the main thread; Bukkit rejects NaN and infinite coordinates with an exception
        return CompletableFuture.supplyAsync(() -> {
            try {
                World world = Bukkit.getWorld(location.worldName());
                if (world == null) return CompletableFuture.completedFuture(TeleportResult.WORLD_NOT_FOUND);
                return bukkit.teleportAsync(new Location(world, location.x(), location.y(), location.z(),
                        location.yaw(), location.pitch()))
                        .thenApply(ok -> ok ? TeleportResult.OK : TeleportResult.CONNECT_FAILED);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Teleport von " + uuid + " fehlgeschlagen", e);
                return CompletableFuture.completedFuture(TeleportResult.CONNECT_FAILED);
            }
        }, mainThread).thenCompose(f -> f).exceptionally(e -> {
            LOG.log(Level.WARNING, "Teleport von " + uuid + " fehlgeschlagen", e);
            return TeleportResult.CONNECT_FAILED;
        });
    }

    @Override
    public CompletableFuture<TeleportResult> connect(String server) {
        Objects.requireNonNull(server, "server");
        if (!ended && server.equals(nodeId)) return CompletableFuture.completedFuture(TeleportResult.OK);
        return network.connect(server);
    }

    @Override
    public void sendMessage(MessageKey key, Arg... args) {
        network.sendMessage(key, args);
    }

    @Override
    public void sendActionBar(MessageKey key, Arg... args) {
        network.sendActionBar(key, args);
    }

    @Override
    public void showTitle(MessageKey key, Arg... args) {
        network.showTitle(key, args);
    }

    @Override
    public Audience audience() {
        return ended ? network.audience() : bukkit; // the network view uses this handle as audience (R9)
    }

    /** Adventure would split the title into parts; hand it on whole. */
    @Override
    public void showTitle(Title title) {
        audience().showTitle(title);
    }

    /** R52: equal iff the other is a PanPlayer with the same UUID. */
    @Override
    public boolean equals(Object o) {
        return o instanceof PanPlayer p && p.uniqueId().equals(uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public String toString() {
        return "LocalPanPlayer[" + uuid + (ended ? ", quit" : "") + "]";
    }
}
