package de.kalypzo.pantheon.core.language;

import static de.kalypzo.pantheon.language.ThemeStyle.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemeLoaderTest {

    @TempDir
    Path dir;

    private final List<LogRecord> records = new ArrayList<>();
    private Logger log;

    @BeforeEach
    void setUp() {
        log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
    }

    private ThemeImpl parse(String yaml) {
        return ThemeLoader.parse(new StringReader(yaml), log);
    }

    private List<LogRecord> at(Level level) {
        return records.stream().filter(r -> r.getLevel().equals(level)).toList();
    }

    @Test
    void missingFileWritesDefault() {
        Path file = dir.resolve("theme.yml");
        ThemeImpl t = ThemeLoader.load(file, log);
        assertThat(file).exists();
        assertThat(t.hash()).isEqualTo(ThemeImpl.DEFAULT.hash());
        assertThat(at(Level.INFO)).anyMatch(r -> r.getMessage().contains("Theme " + ThemeImpl.DEFAULT.hash()));
    }

    @Test
    void bundledDefaultParsesToDefault() throws IOException {
        try (Reader r = new InputStreamReader(
                ThemeLoader.class.getResourceAsStream("/pantheon/theme.yml"), StandardCharsets.UTF_8)) {
            assertThat(ThemeLoader.parse(r, log).hash()).isEqualTo(ThemeImpl.DEFAULT.hash());
        }
        assertThat(at(Level.WARNING)).isEmpty();
    }

    @Test
    void shorthandSetsColor() {
        ThemeImpl t = parse("styles:\n  success: \"#112233\"\n");
        assertThat(t.color(SUCCESS)).isEqualTo(TextColor.color(0x112233));
        assertThat(t.style(P)).isEqualTo(ThemeImpl.DEFAULT.style(P));
    }

    @Test
    void objectMergesWithDefault() {
        ThemeImpl t = parse("styles:\n  p:\n    bold: true\n");
        assertThat(t.color(P)).isEqualTo(TextColor.color(0xd4d4d4));
        assertThat(t.style(P).decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(t.style(P).decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.TRUE);
    }

    @Test
    void namedColorCaseInsensitive() {
        assertThat(parse("styles:\n  deny: DARK_RED\n").color(DENY)).isEqualTo(NamedTextColor.DARK_RED);
    }

    @Test
    void unquotedHashIsWarnedAndDefaulted() {
        ThemeImpl t = parse("styles:\n  success: #7ef874\n");
        assertThat(t.color(SUCCESS)).isEqualTo(ThemeImpl.DEFAULT.color(SUCCESS));
        assertThat(at(Level.WARNING)).anyMatch(r -> r.getMessage().contains("success"));
    }

    @Test
    void numberIsWarnedAndDefaulted() {
        ThemeImpl t = parse("styles:\n  success: 0x7ef874\n");
        assertThat(t.color(SUCCESS)).isEqualTo(ThemeImpl.DEFAULT.color(SUCCESS));
        assertThat(at(Level.WARNING)).hasSize(1);
    }

    @Test
    void shortOrBareHexInvalid() {
        ThemeImpl t = parse("styles:\n  success: \"#fff\"\n  deny: \"7ef874\"\n");
        assertThat(t.hash()).isEqualTo(ThemeImpl.DEFAULT.hash());
        assertThat(at(Level.WARNING)).hasSize(2);
    }

    @Test
    void invalidFieldDefaultsWholeEntry() {
        ThemeImpl t = parse("styles:\n  h1:\n    color: \"#ffffff\"\n    bold: \"ja\"\n");
        assertThat(t.color(H1)).isEqualTo(TextColor.color(0xffedd5));
        assertThat(at(Level.WARNING)).anyMatch(r -> r.getMessage().contains("h1"));
    }

    @Test
    void unknownTagAndKeyWarned() {
        ThemeImpl t = parse("styles:\n  h3: \"#ffffff\"\n  p:\n    colour: \"#fff000\"\n");
        assertThat(t.hash()).isEqualTo(ThemeImpl.DEFAULT.hash());
        assertThat(at(Level.WARNING)).hasSize(2);
    }

    @Test
    void emptyFileIsDefaultWithoutSevere() {
        assertThat(parse("").hash()).isEqualTo(ThemeImpl.DEFAULT.hash());
        assertThat(parse("# nur Kommentar\n").hash()).isEqualTo(ThemeImpl.DEFAULT.hash());
        assertThat(at(Level.SEVERE)).isEmpty();
    }

    @Test
    void brokenYamlIsSevere() {
        assertThat(parse("styles: [unclosed").hash()).isEqualTo(ThemeImpl.DEFAULT.hash());
        assertThat(at(Level.SEVERE)).hasSize(1);
    }

    @Test
    void unwritableFolderStillStarts() throws IOException {
        Files.writeString(dir.resolve("blocker"), "x");
        ThemeImpl t = ThemeLoader.load(dir.resolve("blocker/theme.yml"), log);
        assertThat(t.hash()).isEqualTo(ThemeImpl.DEFAULT.hash());
        assertThat(at(Level.WARNING)).isNotEmpty();
    }
}
