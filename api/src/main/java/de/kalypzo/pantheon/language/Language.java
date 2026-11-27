package de.kalypzo.pantheon.language;

import java.util.Locale;
import net.kyori.adventure.audience.Audience;

/** The translations of one plugin. */
public interface Language {

    Message message(MessageKey key);

    /** Shorthand for {@code message(key).with(args).send(audience)}. */
    void send(Audience audience, MessageKey key, Arg... args);

    /** The language of the recipient. Never null. */
    Locale locale(Audience audience);

    Locale defaultLocale();

    /** Re-reads all sources, checks again and drops cached renderings. */
    void reload();
}
