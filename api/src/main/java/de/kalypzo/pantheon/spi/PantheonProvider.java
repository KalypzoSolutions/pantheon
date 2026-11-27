package de.kalypzo.pantheon.spi;

import de.kalypzo.pantheon.Pantheon;
import java.util.function.Function;
import org.bukkit.plugin.java.JavaPlugin;

/** Registration point used by the Pantheon plugin itself. Plugins call {@link Pantheon#get}. */
public final class PantheonProvider {

    private static volatile Function<JavaPlugin, Pantheon> factory;

    private PantheonProvider() {}

    public static void register(Function<JavaPlugin, Pantheon> factory) {
        PantheonProvider.factory = java.util.Objects.requireNonNull(factory, "factory");
    }

    public static void unregister() {
        factory = null;
    }

    public static Pantheon get(JavaPlugin plugin) {
        Function<JavaPlugin, Pantheon> f = factory;
        if (f == null) {
            throw new IllegalStateException(
                    "Pantheon ist nicht geladen. Steht \"depend: [Pantheon]\" in der plugin.yml?");
        }
        return f.apply(plugin);
    }
}
