package de.kalypzo.pantheon.player;

import de.kalypzo.pantheon.audit.AuditLog;
import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.world.NetworkLocation;
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

    void sendMessage(MessageKey key, Arg... args);

    void sendActionBar(MessageKey key, Arg... args);

    void showTitle(MessageKey key, Arg... args);
}
