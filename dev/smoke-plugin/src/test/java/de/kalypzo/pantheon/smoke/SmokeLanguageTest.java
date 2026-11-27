package de.kalypzo.pantheon.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.LanguageAssertions;
import org.junit.jupiter.api.Test;

class SmokeLanguageTest {

    /** The only finding is the intentionally missing key (checklist: the P-19 message appears at startup). */
    @Test
    void onlyTheIntentionalTranslationIsMissing() {
        assertThatThrownBy(() -> LanguageAssertions.assertComplete(SmokePlugin.LANGUAGE_NAME, SmokeMessage.class))
                .isInstanceOf(AssertionError.class)
                .hasMessage("pantheonsmoke_en_US.yml: Key fehlt: smoke.untranslated (SmokeMessage.UNTRANSLATED)");
    }

    @Test
    void placeholderNamesAreValid() {
        for (String name : SmokeMessage.PLACEHOLDERS) {
            assertThat(Arg.isValidName(name)).as(name).isTrue();
        }
    }
}
