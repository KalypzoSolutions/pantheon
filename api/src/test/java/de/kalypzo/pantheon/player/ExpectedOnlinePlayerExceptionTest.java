package de.kalypzo.pantheon.player;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExpectedOnlinePlayerExceptionTest {

    @Test
    void messageContainsUuidAndScope() {
        UUID id = UUID.randomUUID();
        var e = new ExpectedOnlinePlayerException(id, ExpectedOnlinePlayerException.Scope.LOCAL);
        assertThat(e.getMessage()).contains(id.toString()).contains("LOCAL");
        assertThat(e.uuid()).isEqualTo(id);
        assertThat(e.scope()).isEqualTo(ExpectedOnlinePlayerException.Scope.LOCAL);
    }
}
