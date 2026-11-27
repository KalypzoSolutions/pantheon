package de.kalypzo.pantheon.core.language;

import java.util.Map;

/** Plural selection (E17): {@code one} for n == 1, otherwise {@code other}; a missing form falls back to {@code other}. */
public final class PluralRules {

    private PluralRules() {}

    public static String category(long n) {
        return n == 1 ? "one" : "other";
    }

    /** The template for n, or {@code null} if the map has neither the form nor {@code other}. */
    public static String select(Map<String, String> forms, long n) {
        String form = forms.get(category(n));
        return form != null ? form : forms.get("other");
    }
}
