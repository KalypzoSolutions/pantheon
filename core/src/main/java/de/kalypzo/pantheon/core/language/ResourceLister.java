package de.kalypzo.pantheon.core.language;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Lists and opens resources of a plugin jar. On Paper it searches the jar via its CodeSource. */
public interface ResourceLister {

    /** Resource paths (e.g. {@code lang/lobby_de_DE.yml}) directly inside {@code directory}. */
    List<String> list(String directory) throws IOException;

    /** Opens a resource, or returns {@code null} if it does not exist. */
    @Nullable InputStream open(String path) throws IOException;

    /** Resources taken from a directory on disk. */
    static ResourceLister ofDirectory(Path root) {
        return new ResourceLister() {
            @Override
            public List<String> list(String directory) throws IOException {
                Path dir = root.resolve(directory);
                if (!Files.isDirectory(dir)) {
                    return List.of();
                }
                try (Stream<Path> s = Files.list(dir)) {
                    return s.filter(Files::isRegularFile)
                            .map(p -> directory + "/" + p.getFileName())
                            .sorted()
                            .toList();
                }
            }

            @Override
            public @Nullable InputStream open(String path) throws IOException {
                Path p = root.resolve(path);
                return Files.isRegularFile(p) ? Files.newInputStream(p) : null;
            }
        };
    }
}
