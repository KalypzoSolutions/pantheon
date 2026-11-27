package de.kalypzo.pantheon.core.language;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One translated entry of a language file. */
public sealed interface LanguageEntry {

    record Text(String value) implements LanguageEntry {}

    record Lines(List<String> lines) implements LanguageEntry {
        public Lines {
            lines = List.copyOf(lines);
        }
    }

    record TitleEntry(String title, @Nullable String subtitle) implements LanguageEntry {}

    /** Forms keyed by {@code zero|one|two|few|many|other}; {@code other} is always present. */
    record Plural(Map<String, String> forms) implements LanguageEntry {
        public Plural {
            forms = Map.copyOf(forms);
        }
    }
}
