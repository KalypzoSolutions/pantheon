package de.kalypzo.pantheon.core.language;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Finds the entry for a key: exact locale, same language, default locale. */
public final class LocaleResolver {

    private final Map<Locale, Map<String, LanguageEntry>> entries;

    public LocaleResolver(Map<Locale, Map<String, LanguageEntry>> entries) {
        this.entries = Map.copyOf(entries);
    }

    public Optional<LanguageEntry> resolve(String key, Locale requested, Locale defaultLocale) {
        Optional<LanguageEntry> found = lookup(key, requested);
        return found.isPresent() ? found : lookup(key, defaultLocale);
    }

    private Optional<LanguageEntry> lookup(String key, Locale locale) {
        LanguageEntry exact = entry(locale, key);
        if (exact != null) {
            return Optional.of(exact);
        }
        String language = locale.getLanguage();
        LanguageEntry canonical = entry(Locale.of(language, language.toUpperCase(Locale.ROOT)), key);
        if (canonical != null) {
            return Optional.of(canonical);
        }
        return entries.entrySet().stream()
                .filter(e -> e.getKey().getLanguage().equals(language) && e.getValue().containsKey(key))
                .min(Map.Entry.comparingByKey(java.util.Comparator.comparing(Locale::toString)))
                .map(e -> e.getValue().get(key));
    }

    private LanguageEntry entry(Locale locale, String key) {
        Map<String, LanguageEntry> m = entries.get(locale);
        return m == null ? null : m.get(key);
    }
}
