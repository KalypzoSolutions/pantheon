package de.kalypzo.pantheon.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** P-33. */
class ChatAuditTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();

    @Test
    void cancelledMarked() {
        AuditRecord r = ChatAudit.of(PLAYER, "hallo", true, null).build(ID, null);
        assertThat(r.action()).isEqualTo("pantheon:chat");
        assertThat(r.with()).containsEntry("text", "hallo").containsEntry("cancelled", true);
    }

    @Test
    void longTextTruncated() {
        AuditRecord r = ChatAudit.of(PLAYER, "a".repeat(700), false, null).build(ID, null);
        assertThat((String) r.with().get("text")).hasSize(512);
        assertThat(r.with()).containsEntry("truncated", true);
    }

    @Test
    void positionOptional() {
        assertThat(ChatAudit.of(PLAYER, "x", false, null).build(ID, null).position()).isNull();
        Position p = new Position("world", 1, 2, 3);
        assertThat(ChatAudit.of(PLAYER, "x", false, p).build(ID, null).position()).isEqualTo(p);
    }
}
