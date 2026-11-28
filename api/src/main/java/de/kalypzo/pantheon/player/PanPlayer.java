package de.kalypzo.pantheon.player;

import de.kalypzo.pantheon.audit.AuditLog;
import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.world.NetworkLocation;
import de.kalypzo.pantheon.world.TeleportResult;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.audience.ForwardingAudience;

/** A player somewhere in the network. Thread safe. */
public interface PanPlayer extends ForwardingAudience.Single {

    /** Audit entries about this player, also for players on other servers. */
    AuditLog audit();

    UUID uniqueId();

    String name();

    Locale lang();

    /** Online anywhere in the network. */
    boolean isOnline();

    /** On this server. */
    boolean isLocal();

    /** Current server, empty if offline. */
    Optional<String> server();

    /** The synchronous side, if the player is on this server. */
    Optional<LocalPanPlayer> local();

    CompletableFuture<NetworkLocation> location();

    /**
     * Brings the player to {@code location}, also to another server. The future always completes normally with a
     * result, never with an exception (except for programming errors such as {@code null}). Callbacks may run on any
     * thread; for Bukkit calls use {@code thenAcceptAsync(..., pantheon.mainThread())}.
     */
    CompletableFuture<TeleportResult> teleport(NetworkLocation location);

    /**
     * Connects the player to {@code server} without a target location (the normal spawn). {@code OK} means the join
     * on the target is done and its data is loaded. If the player is already there: {@code OK} at once, nothing
     * happens. Completes like {@link #teleport(NetworkLocation)}.
     */
    CompletableFuture<TeleportResult> connect(String server);

    void sendMessage(MessageKey key, Arg... args);

    void sendActionBar(MessageKey key, Arg... args);

    void showTitle(MessageKey key, Arg... args);
}
