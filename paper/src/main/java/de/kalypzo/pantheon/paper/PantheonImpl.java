package de.kalypzo.pantheon.paper;

import de.kalypzo.pantheon.Pantheon;
import de.kalypzo.pantheon.PantheonConfig;
import de.kalypzo.pantheon.core.language.LanguageBootstrap;
import de.kalypzo.pantheon.core.language.LanguageConfigImpl;
import de.kalypzo.pantheon.core.language.LanguageImpl;
import de.kalypzo.pantheon.core.runtime.PluginScope;
import de.kalypzo.pantheon.core.store.StoreRegistry;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.data.Store;
import de.kalypzo.pantheon.language.Language;
import de.kalypzo.pantheon.language.LanguageConfig;
import de.kalypzo.pantheon.language.LanguageProvider;
import de.kalypzo.pantheon.language.LanguageSource;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.language.MessageType;
import de.kalypzo.pantheon.language.StyleBuilder;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.PanPlayer;
import de.kalypzo.pantheon.player.PlayerService;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Pantheon instance of one plugin. {@code language()} and {@code config()} are its own (files
 * {@code lang/<plugin in lower case>_<locale>.yml} of its jar and data folder, plus Pantheon's {@code pantheon.*});
 * players and stores are the shared ones of the {@link PantheonRuntime}, seen through this {@link PluginScope}, so a
 * {@code PluginDisableEvent} removes every listener and cached share of the plugin (megalodon §1). Thread-safe.
 */
public final class PantheonImpl extends PluginScope implements Pantheon {

    private final PantheonRuntime runtime;
    private final LanguageImpl language;
    private final PantheonConfig config;
    private final PlayerService players;
    private final LanguageConfigImpl languageConfig;

    public PantheonImpl(JavaPlugin plugin, PantheonRuntime runtime) {
        Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        String name = plugin.getName().toLowerCase(Locale.ROOT);
        LanguageConfigImpl languageConfig = new LanguageConfigImpl(name);
        PaperArgAdapters.register(languageConfig);
        this.languageConfig = languageConfig;
        // P-18/P-19: loads the files and runs the check for the enums registered so far
        this.language = LanguageBootstrap.create(name, languageConfig, runtime.languageHooks(),
                PaperResourceLister.of(plugin.getClass()), runtime.pantheonResources(),
                plugin.getDataFolder().toPath(), plugin.getLogger(),
                () -> de.kalypzo.pantheon.core.language.ThemeImpl.DEFAULT);
        LanguageConfig checked = new CheckedLanguageConfig(languageConfig, language);
        this.config = () -> checked;
        this.players = new PaperPlayerService(runtime, this, language, this::languageOf);
    }

    /** The language of a player: the provider the plugin configured, the default language for the marker. */
    private Locale languageOf(PanPlayer player) {
        LanguageProvider configured = languageConfig.provider();
        return configured == LanguageProvider.defaultLocale() ? language.defaultLocale() : configured.lang(player);
    }

    /** The service of this plugin, for the runtime. */
    PaperPlayerService paperPlayers() {
        return (PaperPlayerService) players;
    }

    @Override
    public LocalPanPlayer getPlayer(Player player) {
        return players.local(player);
    }

    @Override
    public PlayerService players() {
        return players;
    }

    @Override
    public Store store(DataNamespace namespace, String name) {
        StoreRegistry stores = runtime.started().stores();
        return store(stores.store(namespace, name), stores);
    }

    @Override
    public Language language() {
        return language;
    }

    /** The node's audit API: one buffer per node for all plugins (audit §7). */
    @Override
    public de.kalypzo.pantheon.audit.AuditService audit() {
        return runtime.audit();
    }

    @Override
    public PantheonConfig config() {
        return config;
    }

    @Override
    public Executor mainThread() {
        return runtime.mainThread();
    }

    /** The plugin's language config; {@code register} runs the language check again (P-19). */
    private static final class CheckedLanguageConfig implements LanguageConfig {
        private final LanguageConfigImpl delegate;
        private final LanguageImpl language;

        CheckedLanguageConfig(LanguageConfigImpl delegate, LanguageImpl language) {
            this.delegate = delegate;
            this.language = language;
        }

        @Override
        public LanguageConfig defaultLocale(Locale locale) {
            delegate.defaultLocale(locale);
            return this;
        }

        @Override
        public LanguageConfig prefix(String miniMessage) {
            delegate.prefix(miniMessage);
            return this;
        }

        @Override
        public LanguageConfig prefix(MessageKey key) {
            delegate.prefix(key);
            return this;
        }

        @Override
        public LanguageConfig style(MessageType type, Consumer<StyleBuilder> style) {
            delegate.style(type, style);
            return this;
        }

        @Override
        public <T> LanguageConfig adapter(Class<T> type, BiFunction<T, Locale, Component> adapter) {
            delegate.adapter(type, adapter);
            return this;
        }

        @Override
        @SafeVarargs
        @SuppressWarnings("varargs")
        public final LanguageConfig register(Class<? extends MessageKey>... keys) {
            delegate.register(keys);
            language.reload(); // reads the files again and checks the enums registered now
            return this;
        }

        @Override
        public LanguageConfig source(LanguageSource... sources) {
            delegate.source(sources);
            return this;
        }

        @Override
        public LanguageConfig provider(LanguageProvider provider) {
            delegate.provider(provider);
            return this;
        }
    }
}
