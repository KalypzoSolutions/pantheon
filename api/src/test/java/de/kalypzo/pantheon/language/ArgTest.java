package de.kalypzo.pantheon.language;

import static de.kalypzo.pantheon.language.Arg.arg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ArgTest {

    @Test
    void rejectsCamelCase() {
        assertThatThrownBy(() -> arg("targetName", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReservedScore() {
        assertThatThrownBy(() -> arg("score", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Arg.parsed("prefix", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arg("count", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arg("highlight", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arg("h1", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arg("h2", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTemplateTagContent() {
        assertThatThrownBy(() -> arg("<red>", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arg("a:b", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> arg("", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsSnakeCaseAndDash() {
        assertThat(arg("target_name", 1)).isEqualTo(new Arg.Value("target_name", 1));
        assertThat(arg("target-name2", "x")).isEqualTo(new Arg.Value("target-name2", "x"));
        assertThat(Arg.parsed("motd", "<red>hi")).isEqualTo(new Arg.Parsed("motd", "<red>hi"));
    }
}
