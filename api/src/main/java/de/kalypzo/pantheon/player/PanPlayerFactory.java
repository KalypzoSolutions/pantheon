package de.kalypzo.pantheon.player;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

public interface PanPlayerFactory {

    /** Cannot fail. */
    LocalPanPlayer local(Player player);

    Optional<PanPlayer> online(UUID uuid);

    Optional<PanPlayer> online(String name);

    /** The whole network. */
    Collection<PanPlayer> online();

    /** Only this server. */
    Collection<LocalPanPlayer> onlineLocal();
}
