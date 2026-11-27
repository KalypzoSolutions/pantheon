package de.kalypzo.pantheon.language;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MessageTypeTest {

    @Test
    void customTypesEqualByName() {
        assertThat(MessageType.of("reward")).isEqualTo(MessageType.of("reward"))
                .hasSameHashCodeAs(MessageType.of("reward"));
        assertThat(MessageType.of("reward")).isNotEqualTo(MessageType.of("bonus"));
        assertThat(MessageType.of("success")).isEqualTo(MessageType.SUCCESS);
    }

    @Test
    void rejectsInvalidTypeName() {
        assertThatThrownBy(() -> MessageType.of("Reward")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageType.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageType.of("a b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builtinsAreDistinct() {
        assertThat(Set.of(MessageType.PLAIN, MessageType.PROMPT, MessageType.DEFAULT, MessageType.SUCCESS,
                MessageType.DENY, MessageType.ERROR)).hasSize(6);
    }
}
