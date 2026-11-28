package de.kalypzo.pantheon.world;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.language.CommonMessage;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class TeleportResultTest {

    @Test
    void messageForEveryFailure() {
        assertThat(TeleportResult.OK.message()).isEmpty();
        assertThat(TeleportResult.SUPERSEDED.message()).isEmpty();
        for (TeleportResult r : EnumSet.complementOf(EnumSet.of(TeleportResult.OK, TeleportResult.SUPERSEDED))) {
            assertThat(r.message()).contains(CommonMessage.valueOf("TELEPORT_" + r.name()));
        }
        assertThat(TeleportResult.OK.ok()).isTrue();
        assertThat(TeleportResult.TIMEOUT.ok()).isFalse();
    }
}
