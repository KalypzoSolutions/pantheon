package de.kalypzo.pantheon.core.language;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YamlLanguageLoaderTest {

    private static Map<String, LanguageEntry> load(String yaml) {
        return YamlLanguageLoader.load(new StringReader(yaml));
    }

    @Test
    void nestedKeysJoinWithDots() {
        var m = load("shop:\n  bought: \"Gekauft\"\n  too-poor: \"Zu arm\"\n");
        assertThat(m).containsEntry("shop.bought", new LanguageEntry.Text("Gekauft"))
                .containsEntry("shop.too-poor", new LanguageEntry.Text("Zu arm"));
    }

    @Test
    void listBecomesLines() {
        var m = load("kit:\n  lore:\n    - \"a\"\n    - \"\"\n    - \"b\"\n");
        assertThat(m).containsEntry("kit.lore", new LanguageEntry.Lines(List.of("a", "", "b")));
    }

    @Test
    void titleMap() {
        var m = load("round:\n  start:\n    title: \"T\"\n    subtitle: \"S\"\n  end:\n    title: \"only\"\n");
        assertThat(m).containsEntry("round.start", new LanguageEntry.TitleEntry("T", "S"))
                .containsEntry("round.end", new LanguageEntry.TitleEntry("only", null));
    }

    @Test
    void pluralMap() {
        var m = load("coins:\n  one: \"<count> Münze\"\n  other: \"<count> Münzen\"\n");
        assertThat(m).containsEntry("coins",
                new LanguageEntry.Plural(Map.of("one", "<count> Münze", "other", "<count> Münzen")));
    }

    @Test
    void dottedKeyInsideMap() {
        var m = load("kit:\n  warrior.name: \"Krieger\"\n");
        assertThat(m).containsEntry("kit.warrior.name", new LanguageEntry.Text("Krieger"));
    }
}
