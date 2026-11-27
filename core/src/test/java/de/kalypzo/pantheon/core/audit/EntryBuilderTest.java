package de.kalypzo.pantheon.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** P-31 / E32. */
class EntryBuilderTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();

    private static EntryBuilder builder() {
        return new EntryBuilder(PLAYER, "shop:buy");
    }

    @Test
    void rejectsInvalidKey() {
        assertThatThrownBy(() -> builder().with("Item", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().with("item-x", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().with("", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSeventeenthKey() {
        EntryBuilder b = builder();
        for (int i = 0; i < 16; i++) {
            b.with("k" + i, i);
        }
        assertThatThrownBy(() -> b.with("k16", 1L)).isInstanceOf(IllegalArgumentException.class);
        // replacing an existing key is not a 17th key
        b.with("k0", 99L);
        assertThat(b.build(ID, null).with()).hasSize(16).containsEntry("k0", 99L);
    }

    @Test
    void truncatesTextAt512AndMarks() {
        AuditRecord r = builder().with("text", "a".repeat(600)).build(ID, null);

        assertThat((String) r.with().get("text")).hasSize(512);
        assertThat(r.with()).containsEntry("truncated", true);
    }

    @Test
    void truncatedDoesNotCountTowardsLimit() {
        EntryBuilder b = builder();
        b.with("k0", "a".repeat(600));
        for (int i = 1; i < 16; i++) {
            b.with("k" + i, i);
        }
        AuditRecord r = b.build(ID, null);

        assertThat(r.with()).hasSize(17).containsEntry("truncated", true);
        assertThatThrownBy(() -> b.with("k16", 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().with("truncated", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAllValueTypes() {
        AuditRecord r = builder().with("s", "text").with("l", 5L).with("i", 3).with("d", 1.5).with("b", true)
                .build(ID, null);

        assertThat(r.with()).containsEntry("s", "text").containsEntry("l", 5L).containsEntry("i", 3L)
                .containsEntry("d", 1.5).containsEntry("b", true);
    }

    @Test
    void rejectsOtherValueTypes() {
        assertThatThrownBy(() -> builder().with("o", new Object())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().with("n", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().with("d", Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().with("d", Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().with("d", Float.NEGATIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nulInStringsIsReplaced() {
        AuditRecord r = builder().with("s", "a\0b").build(ID, null);

        assertThat(r.with()).containsEntry("s", "a�b");
    }

    @Test
    void entitySetsThreeKeys() {
        UUID e = UUID.randomUUID();
        AuditRecord r = builder().entity("zombie", e, "Bob").build(ID, null);

        assertThat(r.with()).containsEntry("entity_type", "zombie").containsEntry("entity_uuid", e.toString())
                .containsEntry("entity_name", "Bob");
    }

    @Test
    void entityLastCallWinsAndNullNameOmitted() {
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        AuditRecord r = builder().entity("zombie", e1, "Bob").entity("cow", e2, null).build(ID, null);

        assertThat(r.with()).containsEntry("entity_type", "cow").containsEntry("entity_uuid", e2.toString())
                .doesNotContainKey("entity_name");
    }

    @Test
    void targetLastWinsAndTraceHandling() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID explicit = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        EntryBuilder b = builder().target(t1).target(t2);

        assertThat(b.explicitTrace()).isNull();
        assertThat(b.build(ID, active).trace()).isEqualTo(active);
        assertThat(b.build(ID, active).target()).isEqualTo(t2);
        b.trace(explicit);
        assertThat(b.explicitTrace()).isEqualTo(explicit);
        assertThat(b.build(ID, active).trace()).isEqualTo(explicit);
    }

    @Test
    void rejectsInvalidAction() {
        assertThatThrownBy(() -> new EntryBuilder(PLAYER, "Shop:buy")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntryBuilder(PLAYER, "buy")).isInstanceOf(IllegalArgumentException.class);
    }
}
