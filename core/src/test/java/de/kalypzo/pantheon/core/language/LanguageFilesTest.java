package de.kalypzo.pantheon.core.language;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.kalypzo.pantheon.language.LanguageSource;

class LanguageFilesTest {

    private static final Locale DE_DE = Locale.of("de", "DE");
    private static final Locale EN_US = Locale.of("en", "US");

    @TempDir
    Path tmp;

    private Path write(String rel, String content) throws IOException {
        Path p = tmp.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    private static LanguageEntry text(String s) {
        return new LanguageEntry.Text(s);
    }

    @Test
    void discoversAllLocalesByPattern() throws IOException {
        write("jar/lang/lobby_de_DE.yml", "hello: \"Hallo\"\n");
        write("jar/lang/lobby_en_US.yml", "hello: \"Hello\"\n");
        write("jar/lang/other_de_DE.yml", "hello: \"nein\"\n");
        var res = ResourceLister.ofDirectory(tmp.resolve("jar"));
        var all = LanguageFiles.discover("lobby", List.of(LanguageSource.pluginResources()), res, tmp.resolve("data"));
        assertThat(all).containsOnlyKeys(DE_DE, EN_US);
        assertThat(all.get(DE_DE)).containsEntry("hello", text("Hallo"));
        assertThat(all.get(EN_US)).containsEntry("hello", text("Hello"));
    }

    @Test
    void dataFolderOverridesSingleKeys() throws IOException {
        write("jar/lang/lobby_de_DE.yml", "a: \"jar-a\"\nb: \"jar-b\"\n");
        write("data/lang/lobby_de_DE.yml", "b: \"data-b\"\n");
        var res = ResourceLister.ofDirectory(tmp.resolve("jar"));
        var all = LanguageFiles.discover("lobby",
                List.of(LanguageSource.pluginResources(), LanguageSource.dataFolder()), res, tmp.resolve("data"));
        assertThat(all.get(DE_DE)).containsEntry("a", text("jar-a")).containsEntry("b", text("data-b"));
    }

    @Test
    void pluginKeyOverridesCommonMessage() throws IOException {
        write("pan/lang/pantheon_de_DE.yml", "pantheon:\n  no-permission: \"common\"\n  players-only: \"common2\"\n");
        write("jar/lang/lobby_de_DE.yml", "pantheon:\n  no-permission: \"plugin\"\n");
        var all = LanguageFiles.discover("lobby", List.of(LanguageSource.pluginResources()),
                ResourceLister.ofDirectory(tmp.resolve("jar")), ResourceLister.ofDirectory(tmp.resolve("pan")),
                tmp.resolve("data"));
        Map<String, LanguageEntry> de = all.get(DE_DE);
        assertThat(de).containsEntry("pantheon.no-permission", text("plugin"))
                .containsEntry("pantheon.players-only", text("common2"));
    }
}
