package de.kalypzo.pantheon.core.language;

/** Pantheon's default prefix. The theme tags themselves live in {@link ThemeImpl}. */
public final class ThemeTags {

    private ThemeTags() {}

    /** The prefix used when a plugin configures none, as MiniMessage. */
    public static String defaultPrefix(String pluginName) {
        return "<highlight>" + pluginName + "</highlight><dark_gray> »</dark_gray>";
    }
}
