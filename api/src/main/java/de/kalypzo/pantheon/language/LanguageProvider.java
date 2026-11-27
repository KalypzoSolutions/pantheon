package de.kalypzo.pantheon.language;

import de.kalypzo.pantheon.player.PanPlayer;
import java.util.Locale;

/** Decides which language a player gets. */
@FunctionalInterface
public interface LanguageProvider {

    Locale lang(PanPlayer player);

    /** Marker: {@code core} resolves it to the configured default language. */
    static LanguageProvider defaultLocale() {
        return DefaultHolder.INSTANCE;
    }

    final class DefaultHolder {
        static final LanguageProvider INSTANCE = player -> {
            throw new UnsupportedOperationException("Marker, wird von core aufgelöst");
        };

        private DefaultHolder() {}
    }
}
