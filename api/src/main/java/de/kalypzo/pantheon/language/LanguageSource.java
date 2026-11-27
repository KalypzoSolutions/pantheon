package de.kalypzo.pantheon.language;

/** Where language files come from. Later sources override earlier ones per key. */
public sealed interface LanguageSource {

    /** {@code lang/<plugin>_<locale>.yml} inside the plugin jar. */
    static LanguageSource pluginResources() {
        return PluginResources.INSTANCE;
    }

    /** {@code lang/*.yml} in the plugin's data folder. */
    static LanguageSource dataFolder() {
        return DataFolder.INSTANCE;
    }

    final class PluginResources implements LanguageSource {
        private static final PluginResources INSTANCE = new PluginResources();

        private PluginResources() {}
    }

    final class DataFolder implements LanguageSource {
        private static final DataFolder INSTANCE = new DataFolder();

        private DataFolder() {}
    }
}
