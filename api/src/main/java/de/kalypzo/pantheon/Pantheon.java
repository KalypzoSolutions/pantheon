package de.kalypzo.pantheon;

import de.kalypzo.pantheon.audit.AuditService;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.data.Store;
import de.kalypzo.pantheon.language.Language;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.PlayerService;
import de.kalypzo.pantheon.spi.PantheonProvider;
import java.util.concurrent.Executor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Entry point of the Pantheon API. All methods are thread safe. */
public interface Pantheon {

    /** The instance for the given plugin. */
    static Pantheon get(JavaPlugin plugin) {
        return PantheonProvider.get(plugin);
    }

    /** Shorthand for {@code players().local(player)}. Never fails. */
    LocalPanPlayer getPlayer(Player player);

    PlayerService players();

    Store store(DataNamespace namespace, String name);

    /** The {@link Language} of the plugin this instance belongs to. */
    Language language();

    /** The audit log, see audit_log.md. */
    AuditService audit();

    PantheonConfig config();

    /** The theme of this node from {@code plugins/Pantheon/theme.yml}. The same for every plugin, read only. */
    de.kalypzo.pantheon.language.Theme theme();

    /** Runs tasks on the server main thread. */
    Executor mainThread();
}
