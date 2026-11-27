package de.kalypzo.pantheon.language;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LanguageAssertionsTest {

    enum ShopMessage implements MessageKey {
        BOUGHT("shop.bought"),
        TOO_POOR("shop.too-poor");

        private final String key;

        ShopMessage(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }
    }

    @Test
    void passesForCompleteFiles() {
        assertThatCode(() -> LanguageAssertions.check("goodplug").enums(ShopMessage.class).allowUnused("kit.")
                .assertComplete()).doesNotThrowAnyException();
    }

    @Test
    void unusedKeysFailWithoutAllowUnused() {
        assertThatThrownBy(() -> LanguageAssertions.assertComplete("goodplug", ShopMessage.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("goodplug_de_DE.yml: Key wird nicht verwendet: kit.warrior.name");
    }

    @Test
    void failsListingAllFindings() {
        assertThatThrownBy(() -> LanguageAssertions.assertComplete("badplug", ShopMessage.class))
                .isInstanceOf(AssertionError.class)
                .satisfies(e -> assertThat(e.getMessage()).isEqualTo(
                        "badplug_de_DE.yml: Key wird nicht verwendet: shop.old-banner\n"
                                + "badplug_en_US.yml: Key fehlt: shop.too-poor (ShopMessage.TOO_POOR)\n"
                                + "badplug_en_US.yml: Platzhalter weicht ab bei shop.bought: de_DE hat <price>, "
                                + "en_US hat <cost>"));
    }
}
