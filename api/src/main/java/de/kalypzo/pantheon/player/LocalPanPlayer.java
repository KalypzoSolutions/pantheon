package de.kalypzo.pantheon.player;

import de.kalypzo.pantheon.data.Doc;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** A player on this server. Everything is synchronous. */
public interface LocalPanPlayer extends PanPlayer {

    /** @throws ExpectedOnlinePlayerException if the player has left this server */
    Player bukkit();

    boolean hasPermission(String permission);

    /** The player data of the calling plugin. */
    Doc data();

    <T> Optional<T> session(SessionData<T> key);

    <T> void session(SessionData<T> key, @Nullable T value);
}
