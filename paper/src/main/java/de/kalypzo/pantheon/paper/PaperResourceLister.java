package de.kalypzo.pantheon.paper;

import de.kalypzo.pantheon.core.language.ResourceLister;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;

/**
 * Lists the resources of a plugin (P-18): its jar, found via the {@link CodeSource} of its main class. If that lists
 * nothing (the class directory of a development build, where resources live in a directory of their own), the class
 * loader's copies of the directory are listed instead. Only files directly inside the directory count.
 */
public final class PaperResourceLister {

    private PaperResourceLister() {}

    /** The resources of the jar (or class directory) {@code anchor} was loaded from, falling back to its class loader. */
    public static ResourceLister of(Class<?> anchor) {
        Objects.requireNonNull(anchor, "anchor");
        Path location = location(anchor);
        ResourceLister byLoader = ofClassLoader(anchor.getClassLoader());
        if (location == null) return byLoader;
        ResourceLister byLocation = ofLocation(location);
        return new ResourceLister() {
            @Override
            public List<String> list(String directory) throws IOException {
                List<String> own = byLocation.list(directory);
                return own.isEmpty() ? byLoader.list(directory) : own;
            }

            @Override
            public @Nullable InputStream open(String path) throws IOException {
                InputStream in = byLocation.open(path);
                return in != null ? in : byLoader.open(path);
            }
        };
    }

    /** A jar file or a directory. */
    public static ResourceLister ofLocation(Path location) {
        Objects.requireNonNull(location, "location");
        if (Files.isDirectory(location)) return ResourceLister.ofDirectory(location);
        return new ResourceLister() {
            @Override
            public List<String> list(String directory) throws IOException {
                if (!Files.isRegularFile(location)) return List.of();
                try (JarFile jar = new JarFile(location.toFile())) {
                    return entries(jar, directory);
                }
            }

            @Override
            public @Nullable InputStream open(String path) throws IOException {
                if (!Files.isRegularFile(location)) return null;
                try (JarFile jar = new JarFile(location.toFile())) {
                    JarEntry entry = jar.getJarEntry(path);
                    if (entry == null || entry.isDirectory()) return null;
                    try (InputStream in = jar.getInputStream(entry)) {
                        return new ByteArrayInputStream(in.readAllBytes()); // the jar is closed on return
                    }
                }
            }
        };
    }

    /** Every copy of the directory the class loader can see (jar entries or directories). */
    public static ResourceLister ofClassLoader(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return new ResourceLister() {
            @Override
            public List<String> list(String directory) throws IOException {
                TreeSet<String> out = new TreeSet<>();
                Enumeration<URL> urls = loader.getResources(directory);
                while (urls.hasMoreElements()) {
                    URL url = urls.nextElement();
                    if ("file".equals(url.getProtocol())) {
                        try {
                            Path dir = Path.of(url.toURI());
                            out.addAll(ResourceLister.ofDirectory(root(dir, directory))
                                    .list(directory));
                        } catch (URISyntaxException | IllegalArgumentException e) {
                            // not a plain directory
                        }
                    } else if ("jar".equals(url.getProtocol())) {
                        URLConnection c = url.openConnection();
                        c.setUseCaches(false);
                        if (c instanceof JarURLConnection jc) {
                            try (JarFile jar = jc.getJarFile()) {
                                out.addAll(entries(jar, directory));
                            }
                        }
                    }
                }
                return new ArrayList<>(out);
            }

            @Override
            public @Nullable InputStream open(String path) {
                return loader.getResourceAsStream(path);
            }
        };
    }

    private static List<String> entries(JarFile jar, String directory) {
        String prefix = directory.endsWith("/") ? directory : directory + "/";
        TreeSet<String> out = new TreeSet<>();
        jar.stream()
                .filter(e -> !e.isDirectory())
                .map(JarEntry::getName)
                .filter(n -> n.startsWith(prefix) && n.indexOf('/', prefix.length()) < 0)
                .forEach(out::add);
        return new ArrayList<>(out);
    }

    /** The root that contains {@code dir} as {@code directory} (e.g. {@code .../resources} for {@code .../resources/lang}). */
    private static Path root(Path dir, String directory) {
        Path root = dir;
        for (String ignored : directory.split("/")) {
            if (root.getParent() != null) root = root.getParent();
        }
        return root;
    }

    private static @Nullable Path location(Class<?> anchor) {
        try {
            CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | SecurityException | IllegalArgumentException e) {
            return null;
        }
    }
}
