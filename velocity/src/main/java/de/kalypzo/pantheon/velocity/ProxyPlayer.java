package de.kalypzo.pantheon.velocity;

import de.kalypzo.agora.proto.v1.ConnectStatus;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** A player connected to this proxy, as far as Pantheon needs to see it. */
public interface ProxyPlayer {

    UUID uuid();

    String name();

    /** Name of the backend server the player is on, empty before the first connection. */
    Optional<String> currentServer();

    /** Effective locale as {@code de_DE}, or {@code ""} if unknown. */
    String locale();

    void sendMessage(Component message);

    void sendActionBar(Component message);

    void showTitle(Title title);

    /** @return false if this Velocity version cannot play sounds (the sound is discarded) */
    boolean playSound(Sound sound);

    void disconnect(Component reason);

    /** Connects the player to a backend server; never throws, failures complete as {@code FAILED}. */
    CompletableFuture<ConnectStatus> connect(String server);
}
