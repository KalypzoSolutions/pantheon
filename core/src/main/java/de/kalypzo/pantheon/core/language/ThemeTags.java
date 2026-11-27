package de.kalypzo.pantheon.core.language;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Pantheon's theme tags (E16). */
public final class ThemeTags {

    public static final TagResolver RESOLVER = TagResolver.builder()
            .tag("p", Tag.styling(NamedTextColor.GRAY))
            .tag("highlight", Tag.styling(NamedTextColor.GOLD))
            .tag("success", Tag.styling(NamedTextColor.GREEN))
            .tag("deny", Tag.styling(NamedTextColor.RED))
            .tag("error", Tag.styling(NamedTextColor.DARK_RED))
            .tag("prompt", Tag.styling(NamedTextColor.YELLOW))
            .build();

    private ThemeTags() {}

    /** The prefix used when a plugin configures none, as MiniMessage. */
    public static String defaultPrefix(String pluginName) {
        return "<highlight>" + pluginName + "</highlight><dark_gray> »</dark_gray>";
    }
}
