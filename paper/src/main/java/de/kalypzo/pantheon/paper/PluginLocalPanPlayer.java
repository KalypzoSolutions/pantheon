package de.kalypzo.pantheon.paper;

import de.kalypzo.pantheon.core.player.NetworkPanPlayer;
import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.SessionData;
import de.kalypzo.pantheon.world.NetworkLocation;
import de.kalypzo.pantheon.world.TeleportResult;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The {@link LocalPanPlayerImpl} of a player as one plugin sees it: the synchronous side is the shared handle, the
 * PanPlayer side is the {@link NetworkPanPlayer} of that plugin, so messages by key render with its language.
 */
final class PluginLocalPanPlayer implements LocalPanPlayer {

    private final LocalPanPlayer handle;
    private final NetworkPanPlayer network;
    private final Supplier<Optional<LocalPanPlayer>> current;

    /** @param current the view of the handle of the current session, empty if the player is not here */
    PluginLocalPanPlayer(LocalPanPlayer handle, NetworkPanPlayer network,
                         Supplier<Optional<LocalPanPlayer>> current) {
        this.handle = handle;
        this.network = network;
        this.current = current;
    }

    @Override
    public Player bukkit() {
        return handle.bukkit();
    }

    @Override
    public boolean hasPermission(String permission) {
        return handle.hasPermission(permission);
    }

    @Override
    public Doc data() {
        return handle.data();
    }

    @Override
    public <T> Optional<T> session(SessionData<T> key) {
        return handle.session(key);
    }

    @Override
    public <T> void session(SessionData<T> key, @Nullable T value) {
        handle.session(key, value);
    }

    /** The network view's log: the node's one buffer, also after the quit (audit §4). */
    @Override
    public de.kalypzo.pantheon.audit.AuditLog audit() {
        return network.audit();
    }

    @Override
    public UUID uniqueId() {
        return handle.uniqueId();
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
        return current.get(); // a rejoin gives a new handle: never this view's old one
    }

    @Override
    public CompletableFuture<NetworkLocation> location() {
        return network.location();
    }

    @Override
    public CompletableFuture<TeleportResult> teleport(NetworkLocation location) {
        return network.teleport(location);
    }

    @Override
    public CompletableFuture<TeleportResult> connect(String server) {
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
        return network.audience();
    }

    @Override
    public void showTitle(Title title) {
        audience().showTitle(title);
    }

    @Override
    public Pointers pointers() {
        return network.pointers();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof de.kalypzo.pantheon.player.PanPlayer p && p.uniqueId().equals(handle.uniqueId());
    }

    @Override
    public int hashCode() {
        return handle.uniqueId().hashCode();
    }

    @Override
    public String toString() {
        return handle.toString();
    }
}
