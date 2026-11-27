package de.kalypzo.pantheon.language;

import de.kalypzo.pantheon.core.language.CheckReport;
import de.kalypzo.pantheon.core.language.LanguageCheck;
import de.kalypzo.pantheon.core.language.LanguageEntry;
import de.kalypzo.pantheon.core.language.LanguageFiles;
import de.kalypzo.pantheon.core.language.ResourceLister;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Test helper for plugin authors (language.md §10). Runs the same check as the server start against
 * {@code lang/*.yml} on the test classpath. No server needed.
 */
public final class LanguageAssertions {

    private LanguageAssertions() {}

    /**
     * Fails if a language file lacks a key, has an unused key, or has differing or reserved placeholders.
     *
     * @throws AssertionError listing every finding, one per line
     */
    @SafeVarargs
    public static void assertComplete(String plugin, Class<? extends MessageKey>... enums) {
        check(plugin).enums(enums).assertComplete();
    }

    /** For plugins with keys built at runtime: {@code check("lobby").enums(...).allowUnused("kit.").assertComplete()}. */
    public static Check check(String plugin) {
        return new Check(plugin);
    }

    public static final class Check {
        private final String plugin;
        private final List<Class<? extends MessageKey>> enums = new ArrayList<>();
        private final Set<String> allowUnused = new LinkedHashSet<>();

        private Check(String plugin) {
            this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        }

        @SafeVarargs
        public final Check enums(Class<? extends MessageKey>... enums) {
            this.enums.addAll(List.of(enums));
            return this;
        }

        /** Keys starting with one of these prefixes are not reported as unused. */
        public Check allowUnused(String... prefixes) {
            allowUnused.addAll(List.of(prefixes));
            return this;
        }

        public void assertComplete() {
            ResourceLister classpath = new ClasspathLister();
            Map<Locale, Map<String, LanguageEntry>> files = LanguageFiles.discover(plugin,
                    List.of(LanguageSource.pluginResources()), classpath, classpath, Path.of("."));
            CheckReport report = LanguageCheck.check(plugin, files, enums, Map.of(), allowUnused);
            if (!report.ok()) {
                throw new AssertionError(report.assertionMessage());
            }
        }
    }

    /** Lists and opens resources from every {@code lang} directory on the context class path. */
    private static final class ClasspathLister implements ResourceLister {

        private final ClassLoader loader = classLoader();

        private static ClassLoader classLoader() {
            ClassLoader l = Thread.currentThread().getContextClassLoader();
            return l != null ? l : LanguageAssertions.class.getClassLoader();
        }

        @Override
        public List<String> list(String directory) throws IOException {
            Set<String> out = new LinkedHashSet<>();
            for (URL url : Collections.list(loader.getResources(directory))) {
                if ("file".equals(url.getProtocol())) {
                    try (Stream<Path> s = Files.list(Path.of(url.toURI()))) {
                        s.filter(Files::isRegularFile).forEach(p -> out.add(directory + "/" + p.getFileName()));
                    } catch (java.net.URISyntaxException e) {
                        throw new IOException(e);
                    }
                } else if ("jar".equals(url.getProtocol())) {
                    JarURLConnection c = (JarURLConnection) url.openConnection();
                    c.setUseCaches(false);
                    try (JarFile jar = c.getJarFile()) {
                        jar.stream().map(JarEntry::getName)
                                .filter(n -> n.startsWith(directory + "/") && n.indexOf('/', directory.length() + 1) < 0
                                        && !n.endsWith("/"))
                                .forEach(out::add);
                    }
                }
            }
            return out.stream().sorted().toList();
        }

        @Override
        public @Nullable InputStream open(String path) {
            return loader.getResourceAsStream(path);
        }
    }
}
