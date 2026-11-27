package de.kalypzo.pantheon.language;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ThemeStyleTest {

    @Test
    void tagNamesInOrder() {
        assertThat(Arrays.stream(ThemeStyle.values()).map(ThemeStyle::tagName))
                .containsExactly("p", "highlight", "success", "deny", "error", "prompt", "h1", "h2");
    }
}
