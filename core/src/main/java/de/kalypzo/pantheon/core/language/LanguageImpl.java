package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.Language;
import de.kalypzo.pantheon.language.LanguageProvider;
import de.kalypzo.pantheon.language.Message;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.player.PanPlayer;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

/** The Language of one plugin. Thread safe. */
public final class LanguageImpl implements Language {

    private final LanguageConfigImpl config;
    private final LanguageHooks hooks;
    private final Supplier<Renderer> renderer;
    private final Runnable reloader;
    private final RecipientExpander expander;

    /**
     * @param renderer supplies the current renderer, so a reload can swap it
     * @param reloader re-reads sources and checks them again
     */
    public LanguageImpl(String plugin, LanguageConfigImpl config, LanguageHooks hooks, Supplier<Renderer> renderer,
            Runnable reloader) {
        this.config = config;
        this.hooks = hooks;
        this.renderer = renderer;
        this.reloader = reloader;
        this.expander = new RecipientExpander(hooks, this::playerLocale, config::defaultLocale);
    }

    @Override
    public Message message(MessageKey key) {
        return MessageImpl.of(this, key);
    }

    @Override
    public void send(Audience audience, MessageKey key, Arg... args) {
        MessageImpl.of(this, key).withArgs(List.of(args)).send(audience);
    }

    @Override
    public Locale locale(Audience audience) {
        List<RecipientExpander.Recipient> recipients = expander.expand(audience);
        return recipients.size() == 1 ? recipients.get(0).locale() : defaultLocale();
    }

    @Override
    public Locale defaultLocale() {
        return config.defaultLocale();
    }

    @Override
    public void reload() {
        reloader.run();
    }

    private Locale playerLocale(PanPlayer player) {
        LanguageProvider provider = config.provider();
        if (provider == LanguageProvider.defaultLocale()) {
            return hooks.localeOf().apply(player);
        }
        return provider.lang(player);
    }

    Renderer renderer() {
        return renderer.get();
    }

    LanguageConfigImpl config() {
        return config;
    }

    LanguageHooks hooks() {
        return hooks;
    }

    RecipientExpander expander() {
        return expander;
    }

    /** The prefix for a recipient: translated key, configured string, or the default. */
    Component prefix(Locale locale, Audience viewer) {
        MessageKey key = config.prefixKey();
        if (key != null) {
            return renderer().render(key, locale, viewer, List.of(), null);
        }
        return MessageStyles.prefix(config.prefixTemplate());
    }
}
