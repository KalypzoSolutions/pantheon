package de.kalypzo.pantheon.language;

import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

/**
 * The theme of this node, read from {@code plugins/Pantheon/theme.yml} at startup. The same for every plugin on the
 * node and read only. Immutable and thread safe.
 */
public interface Theme {

    /** The style of a theme tag. */
    Style style(ThemeStyle style);

    /** The color of a theme tag. Never {@code null}. */
    TextColor color(ThemeStyle style);
}
