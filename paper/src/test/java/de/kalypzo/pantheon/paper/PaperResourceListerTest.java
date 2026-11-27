package de.kalypzo.pantheon.paper;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.core.language.ResourceLister;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperResourceListerTest {

    @TempDir
    Path dir;

    @Test
    void listsLangFilesOfAJar() throws Exception {
        Path jar = dir.resolve("lobby.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(out)) {
            for (String name : new String[] {"plugin.yml", "lang/", "lang/lobby_de_DE.yml", "lang/lobby_en_US.yml",
                    "lang/sub/deeper_de_DE.yml", "language/other.yml"}) {
                j.putNextEntry(new JarEntry(name));
                if (!name.endsWith("/")) j.write(("k: " + name).getBytes(StandardCharsets.UTF_8));
                j.closeEntry();
            }
        }

        ResourceLister lister = PaperResourceLister.ofLocation(jar);

        assertThat(lister.list("lang")).containsExactly("lang/lobby_de_DE.yml", "lang/lobby_en_US.yml");
        try (InputStream in = lister.open("lang/lobby_de_DE.yml")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("k: lang/lobby_de_DE.yml");
        }
        assertThat(lister.open("lang/missing.yml")).isNull();
    }

    @Test
    void listsLangFilesOfADirectory() throws Exception {
        Files.createDirectories(dir.resolve("lang"));
        Files.writeString(dir.resolve("lang/lobby_de_DE.yml"), "a: b");

        ResourceLister lister = PaperResourceLister.ofLocation(dir);

        assertThat(lister.list("lang")).containsExactly("lang/lobby_de_DE.yml");
        assertThat(lister.list("missing")).isEmpty();
    }

    @Test
    void classLoaderListerFindsPantheonsOwnFiles() throws Exception {
        ResourceLister lister = PaperResourceLister.ofClassLoader(PantheonPlugin.class.getClassLoader());
        assertThat(lister.list("lang")).contains("lang/pantheon_de_DE.yml", "lang/pantheon_en_US.yml");
        try (InputStream in = lister.open("lang/pantheon_de_DE.yml")) {
            assertThat(in).isNotNull();
        }
    }
}
