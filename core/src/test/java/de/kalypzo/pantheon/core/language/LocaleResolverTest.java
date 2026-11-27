package de.kalypzo.pantheon.core.language;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LocaleResolverTest {

    private static final Locale DE_DE = Locale.of("de", "DE");
    private static final Locale DE_AT = Locale.of("de", "AT");
    private static final Locale DE_CH = Locale.of("de", "CH");
    private static final Locale EN_US = Locale.of("en", "US");

    private static Map<String, LanguageEntry> msg(String text) {
        return Map.of("k", new LanguageEntry.Text(text));
    }

    private static Optional<LanguageEntry> text(String s) {
        return Optional.of(new LanguageEntry.Text(s));
    }

    @Test
    void exactWins() {
        var r = new LocaleResolver(Map.of(DE_DE, msg("de"), EN_US, msg("en")));
        assertThat(r.resolve("k", EN_US, DE_DE)).isEqualTo(text("en"));
    }

    @Test
    void sameLanguageOtherCountry() {
        var r = new LocaleResolver(Map.of(DE_DE, msg("de"), EN_US, msg("en")));
        assertThat(r.resolve("k", DE_AT, EN_US)).isEqualTo(text("de"));
    }

    @Test
    void deterministicSameLanguageChoice() {
        var r = new LocaleResolver(Map.of(DE_CH, msg("ch"), DE_DE, msg("de")));
        assertThat(r.resolve("k", DE_AT, EN_US)).isEqualTo(text("de"));
    }

    @Test
    void fallsBackToDefault() {
        var r = new LocaleResolver(Map.of(DE_DE, msg("de")));
        assertThat(r.resolve("k", EN_US, DE_DE)).isEqualTo(text("de"));
    }

    @Test
    void emptyWhenNowhere() {
        var r = new LocaleResolver(Map.of(DE_DE, msg("de")));
        assertThat(r.resolve("other", EN_US, DE_DE)).isEmpty();
    }
}
