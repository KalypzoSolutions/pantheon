package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.language.MessageType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The check of language.md §10. It runs at server start, on reload and in
 * {@code LanguageAssertions}; all three get the same result.
 */
public final class LanguageCheck {

    private static final String OWN_PREFIX = "pantheon.";

    private LanguageCheck() {}

    /**
     * @param files merged entries per locale, as {@link LanguageFiles#discover} returns them
     * @param enums the registered key enums
     * @param styles the styles the plugin configured, to find message types without one
     * @param allowUnused key prefixes of keys that are built at runtime
     */
    public static CheckReport check(String plugin, Map<Locale, Map<String, LanguageEntry>> files,
            List<Class<? extends MessageKey>> enums, Map<MessageType, MessageStyles.Overrides> styles,
            Set<String> allowUnused) {
        Map<String, String> constants = new LinkedHashMap<>(); // key -> Enum.CONSTANT
        Map<MessageType, String> typeFirst = new LinkedHashMap<>();
        for (Class<? extends MessageKey> type : enums) {
            MessageKey[] values = type.getEnumConstants();
            if (values == null) {
                continue;
            }
            for (MessageKey k : values) {
                String name = type.getSimpleName() + "." + ((Enum<?>) k).name();
                constants.putIfAbsent(k.key(), name);
                typeFirst.putIfAbsent(k.type(), name);
            }
        }

        // The plugin's own locales: merged Pantheon files alone do not make a locale.
        Map<Locale, Map<String, LanguageEntry>> own = new TreeMap<>(java.util.Comparator.comparing(Locale::toString));
        files.forEach((locale, entries) -> {
            if (entries.keySet().stream().anyMatch(k -> !k.startsWith(OWN_PREFIX))) {
                own.put(locale, entries);
            }
        });

        List<CheckReport.Finding> findings = new ArrayList<>();
        for (var e : own.entrySet()) {
            String file = plugin + "_" + e.getKey() + ".yml";
            Map<String, LanguageEntry> entries = e.getValue();
            for (var c : constants.entrySet()) {
                if (!entries.containsKey(c.getKey())) {
                    findings.add(new CheckReport.Finding(CheckReport.Kind.MISSING, file, c.getKey(),
                            shadowHint(c.getKey(), entries), c.getValue()));
                }
            }
            // Without a registered enum (the check at instance creation, before onEnable's register) no key is unused.
            for (String key : constants.isEmpty() ? java.util.Set.<String>of() : entries.keySet()) {
                if (!key.startsWith(OWN_PREFIX) && !constants.containsKey(key) && !allowed(key, allowUnused)) {
                    findings.add(new CheckReport.Finding(CheckReport.Kind.UNUSED, file, key, "", null));
                }
            }
        }
        for (var e : own.entrySet()) {
            String file = plugin + "_" + e.getKey() + ".yml";
            for (var entry : e.getValue().entrySet()) {
                if (entry.getKey().startsWith(OWN_PREFIX)) {
                    continue;
                }
                boolean plural = entry.getValue() instanceof LanguageEntry.Plural;
                Set<String> reserved = new LinkedHashSet<>();
                templates(entry.getValue()).forEach(t -> reserved.addAll(Placeholders.reserved(t, plural)));
                for (String name : reserved) {
                    findings.add(new CheckReport.Finding(CheckReport.Kind.RESERVED_PLACEHOLDER, file,
                            entry.getKey(), "<" + name + ">", constants.get(entry.getKey())));
                }
            }
        }
        mismatches(plugin, own, constants, findings);
        typeFirst.forEach((type, constant) -> {
            if (!MessageStyles.hasStyle(type, styles)) {
                findings.add(new CheckReport.Finding(CheckReport.Kind.UNSTYLED_TYPE, "", type.name(), "", constant));
            }
        });
        return new CheckReport(findings);
    }

    /** Every locale is compared with the first one (in locale order) that has the key. */
    private static void mismatches(String plugin, Map<Locale, Map<String, LanguageEntry>> own,
            Map<String, String> constants, List<CheckReport.Finding> findings) {
        Map<String, Locale> reference = new LinkedHashMap<>();
        Map<String, Set<String>> referenceSets = new LinkedHashMap<>();
        for (var e : own.entrySet()) {
            String file = plugin + "_" + e.getKey() + ".yml";
            for (var entry : e.getValue().entrySet()) {
                String key = entry.getKey();
                if (key.startsWith(OWN_PREFIX)) {
                    continue;
                }
                Set<String> set = placeholders(entry.getValue());
                Locale ref = reference.putIfAbsent(key, e.getKey());
                if (ref == null) {
                    referenceSets.put(key, set);
                } else if (!referenceSets.get(key).equals(set)) {
                    findings.add(new CheckReport.Finding(CheckReport.Kind.PLACEHOLDER_MISMATCH, file, key,
                            ref + " hat " + show(referenceSets.get(key)) + ", " + e.getKey() + " hat " + show(set),
                            constants.get(key)));
                }
            }
        }
    }

    private static String show(Set<String> names) {
        if (names.isEmpty()) {
            return "keine";
        }
        return String.join(", ", names.stream().sorted().map(n -> "<" + n + ">").toList());
    }

    private static Set<String> placeholders(LanguageEntry entry) {
        Set<String> out = new LinkedHashSet<>();
        templates(entry).forEach(t -> out.addAll(Placeholders.of(t)));
        return out;
    }

    private static List<String> templates(LanguageEntry entry) {
        return switch (entry) {
            case LanguageEntry.Text t -> List.of(t.value());
            case LanguageEntry.Lines l -> l.lines();
            case LanguageEntry.TitleEntry t -> t.subtitle() == null ? List.of(t.title())
                    : List.of(t.title(), t.subtitle());
            case LanguageEntry.Plural p -> List.copyOf(p.forms().values());
        };
    }

    private static boolean allowed(String key, Set<String> prefixes) {
        return prefixes.stream().anyMatch(key::startsWith);
    }

    /**
     * A map with only {@code title}/{@code subtitle} or with an {@code other} form is read as one title or plural
     * entry (YamlLanguageLoader), so a key like {@code menu.title} is not found. Says so.
     */
    private static String shadowHint(String key, Map<String, LanguageEntry> entries) {
        int dot = key.lastIndexOf('.');
        while (dot > 0) {
            String parent = key.substring(0, dot);
            LanguageEntry e = entries.get(parent);
            if (e instanceof LanguageEntry.TitleEntry || e instanceof LanguageEntry.Plural) {
                return "'" + parent + "' ist als " + (e instanceof LanguageEntry.TitleEntry ? "Title" : "Plural")
                        + "-Eintrag gelesen worden und verdeckt diesen Key";
            }
            dot = parent.lastIndexOf('.');
        }
        return "";
    }
}
