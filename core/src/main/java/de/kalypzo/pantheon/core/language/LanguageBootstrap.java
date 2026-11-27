package de.kalypzo.pantheon.core.language;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Loads the language files of a plugin, checks them and builds its {@link LanguageImpl}. Reload reads every source
 * again, swaps the renderer (which drops everything it remembered) and checks again.
 */
public final class LanguageBootstrap {

    private LanguageBootstrap() {}

    /**
     * @param log the plugin's logger; the check logs at WARNING
     */
    public static LanguageImpl create(String plugin, LanguageConfigImpl config, LanguageHooks hooks,
            ResourceLister resources, ResourceLister pantheonResources, Path dataFolder, Logger log) {
        AtomicReference<Renderer> current = new AtomicReference<>();
        Runnable load = () -> {
            Map<Locale, Map<String, LanguageEntry>> files =
                    LanguageFiles.discover(plugin, config.sources(), resources, pantheonResources, dataFolder);
            current.set(new Renderer(plugin, new LocaleResolver(files), config.adapters(), log, config::defaultLocale));
            CheckReport report = LanguageCheck.check(plugin, files, config.registered(), config.styles(), java.util.Set.of());
            report.logLines().forEach(log::warning);
        };
        load.run();
        return new LanguageImpl(plugin, config, hooks, current::get, load);
    }
}
