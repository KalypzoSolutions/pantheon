package de.kalypzo.pantheon.language;

import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;

/** Configuration of a plugin's {@link Language}. Fluent, every method returns {@code this}. */
public interface LanguageConfig {

    /** Fallback language and language of the console. Default {@code de_DE}. */
    LanguageConfig defaultLocale(Locale locale);

    /** Value of {@code <prefix>} as MiniMessage. */
    LanguageConfig prefix(String miniMessage);

    /** Translated prefix. */
    LanguageConfig prefix(MessageKey key);

    LanguageConfig style(MessageType type, Consumer<StyleBuilder> style);

    <T> LanguageConfig adapter(Class<T> type, BiFunction<T, Locale, Component> adapter);

    /** Makes enums known for the check at startup. */
    @SuppressWarnings("unchecked")
    LanguageConfig register(Class<? extends MessageKey>... keys);

    LanguageConfig source(LanguageSource... sources);

    /** Decides the language of each player. Default {@link LanguageProvider#defaultLocale()}. */
    LanguageConfig provider(LanguageProvider provider);
}
