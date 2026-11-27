package de.kalypzo.pantheon.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SessionDataTest {

    @Test
    void rejectsInvalidName() {
        assertThatThrownBy(() -> SessionData.of("Bad Name")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionData.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThat(SessionData.of("ok_name-1").name()).isEqualTo("ok_name-1");
    }

    @Test
    void sameNameDifferentKeys() {
        SessionData<String> a = SessionData.of("x");
        SessionData<String> b = SessionData.of("x");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo(a);
    }
}
