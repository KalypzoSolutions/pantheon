package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.language.LanguageConfig;
import de.kalypzo.pantheon.language.LanguageProvider;
import de.kalypzo.pantheon.language.LanguageSource;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.language.MessageType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/** The configuration of one plugin's Language. Thread safe. */
public final class LanguageConfigImpl implements LanguageConfig {

    private final String plugin;
    private final ArgAdapters adapters = new ArgAdapters();
    private final Map<MessageType, MessageStyles.Overrides> styles = new ConcurrentHashMap<>();
    private final List<Class<? extends MessageKey>> registered = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile List<LanguageSource> sources = List.of(LanguageSource.pluginResources(), LanguageSource.dataFolder());
    private volatile Locale defaultLocale = Locale.of("de", "DE");
    private volatile @Nullable String prefixString;
    private volatile @Nullable MessageKey prefixKey;
    private volatile LanguageProvider provider = LanguageProvider.defaultLocale();

    public LanguageConfigImpl(String plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public LanguageConfigImpl defaultLocale(Locale locale) {
        this.defaultLocale = Objects.requireNonNull(locale, "locale");
        return this;
    }

    @Override
    public LanguageConfigImpl prefix(String miniMessage) {
        this.prefixString = Objects.requireNonNull(miniMessage, "miniMessage");
        this.prefixKey = null;
        return this;
    }

    @Override
    public LanguageConfigImpl prefix(MessageKey key) {
        this.prefixKey = Objects.requireNonNull(key, "key");
        this.prefixString = null;
        return this;
    }

    @Override
    public LanguageConfigImpl style(MessageType type, Consumer<de.kalypzo.pantheon.language.StyleBuilder> style) {
        style.accept(styles.computeIfAbsent(Objects.requireNonNull(type, "type"), t -> new MessageStyles.Overrides()));
        return this;
    }

    @Override
    public <T> LanguageConfigImpl adapter(Class<T> type, BiFunction<T, Locale, Component> adapter) {
        adapters.register(type, adapter);
        return this;
    }

    @Override
    @SafeVarargs
    public final LanguageConfigImpl register(Class<? extends MessageKey>... keys) {
        registered.addAll(List.of(keys));
        return this;
    }

    @Override
    public LanguageConfigImpl source(LanguageSource... sources) {
        this.sources = List.of(sources);
        return this;
    }

    @Override
    public LanguageConfigImpl provider(LanguageProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
        return this;
    }

    public String plugin() {
        return plugin;
    }

    public Locale defaultLocale() {
        return defaultLocale;
    }

    /** The configured prefix key, if the prefix is translated. */
    public @Nullable MessageKey prefixKey() {
        return prefixKey;
    }

    /** The prefix as MiniMessage: configured, else the default of E16. Not used when {@link #prefixKey()} is set. */
    public String prefixTemplate() {
        String s = prefixString;
        return s != null ? s : ThemeTags.defaultPrefix(plugin);
    }

    public Map<MessageType, MessageStyles.Overrides> styles() {
        return styles;
    }

    public ArgAdapters adapters() {
        return adapters;
    }

    public List<Class<? extends MessageKey>> registered() {
        return new ArrayList<>(registered);
    }

    public List<LanguageSource> sources() {
        return sources;
    }

    public LanguageProvider provider() {
        return provider;
    }
}
