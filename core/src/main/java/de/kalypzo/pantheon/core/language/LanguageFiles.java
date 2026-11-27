package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.language.LanguageSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** Finds and merges language files of one plugin. */
public final class LanguageFiles {

    private static final Logger LOG = Logger.getLogger(LanguageFiles.class.getName());
    private static final String COMMON = "pantheon";

    private LanguageFiles() {}

    /** Same as the five-argument form, with Pantheon's own files taken from {@code resources}. */
    public static Map<Locale, Map<String, LanguageEntry>> discover(
            String plugin, List<LanguageSource> sources, ResourceLister resources, Path dataFolder) {
        return discover(plugin, sources, resources, resources, dataFolder);
    }

    /**
     * Merges Pantheon's own files (lowest), then every source in order; a later source wins per key.
     *
     * @param pantheonResources where {@code lang/pantheon_<locale>.yml} is read from
     */
    public static Map<Locale, Map<String, LanguageEntry>> discover(
            String plugin,
            List<LanguageSource> sources,
            ResourceLister resources,
            ResourceLister pantheonResources,
            Path dataFolder) {
        Map<Locale, Map<String, LanguageEntry>> result = new TreeMap<>(java.util.Comparator.comparing(Locale::toString));
        if (!COMMON.equals(plugin)) {
            fromResources(COMMON, pantheonResources, result);
        }
        for (LanguageSource source : sources) {
            if (source instanceof LanguageSource.PluginResources) {
                fromResources(plugin, resources, result);
            } else if (source instanceof LanguageSource.DataFolder) {
                fromFolder(plugin, dataFolder.resolve("lang"), result);
            }
        }
        return result;
    }

    private static void fromResources(String plugin, ResourceLister lister, Map<Locale, Map<String, LanguageEntry>> out) {
        try {
            for (String path : lister.list("lang")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                Locale locale = localeOf(plugin, name);
                if (locale == null) {
                    continue;
                }
                try (InputStream in = lister.open(path)) {
                    if (in != null) {
                        merge(out, locale, in);
                    }
                } catch (RuntimeException | IOException e) {
                    LOG.log(Level.WARNING, "Cannot read language file " + path, e);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot list language resources of " + plugin, e);
        }
    }

    private static void fromFolder(String plugin, Path dir, Map<Locale, Map<String, LanguageEntry>> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                Locale locale = localeOf(plugin, file.getFileName().toString());
                if (locale == null) {
                    continue;
                }
                try (InputStream in = Files.newInputStream(file)) {
                    merge(out, locale, in);
                } catch (RuntimeException | IOException e) {
                    LOG.log(Level.WARNING, "Cannot read language file " + file, e);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot list " + dir, e);
        }
    }

    private static void merge(Map<Locale, Map<String, LanguageEntry>> out, Locale locale, InputStream in) {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            out.computeIfAbsent(locale, l -> new LinkedHashMap<>()).putAll(YamlLanguageLoader.load(reader));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** {@code <plugin>_<locale>.yml} to a Locale, or {@code null} if the name does not match. */
    static Locale localeOf(String plugin, String fileName) {
        String prefix = plugin + "_";
        if (!fileName.startsWith(prefix) || !fileName.endsWith(".yml")) {
            return null;
        }
        String code = fileName.substring(prefix.length(), fileName.length() - 4);
        String[] parts = code.split("_");
        if (parts.length == 1 && !parts[0].isEmpty()) {
            return Locale.of(parts[0]);
        }
        if (parts.length == 2 && !parts[0].isEmpty()) {
            return Locale.of(parts[0], parts[1]);
        }
        return null;
    }
}
