package de.kalypzo.pantheon.paper;

import de.kalypzo.pantheon.core.language.LanguageConfigImpl;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** The placeholder values Paper adds (language.md §8): a {@code Player} is its display name, an offline one its name. */
final class PaperArgAdapters {

    private PaperArgAdapters() {}

    static void register(LanguageConfigImpl config) {
        config.adapter(Player.class, (player, locale) -> player.displayName());
        config.adapter(OfflinePlayer.class, (player, locale) -> {
            String name = player.getName();
            return Component.text(name != null ? name : player.getUniqueId().toString());
        });
    }
}
