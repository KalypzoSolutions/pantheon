package de.kalypzo.pantheon.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Local state, pending replay and echo detection (P-12, E4). */
class DocHandleTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final FieldSpec COINS = new FieldSpec("economy", "coins", ScalarType.LONG, 0L);
    private static final FieldSpec LEVEL = new FieldSpec("lobby", "level", ScalarType.LONG, 1L);

    private final DocHandle handle = new DocHandle(new PlayerDoc(PLAYER));

    private static Row row(String ns, long version, Map<String, Object> data) {
        return new Row(new PlayerRow(PLAYER, ns), version, data);
    }

    private static DocChanged event(String ns, long version, boolean deleted, Map<String, Object> data, String key) {
        return new DocChanged(new PlayerRow(PLAYER, ns), version, deleted, data, key);
    }

    @Test
    void pendingOpsAreVisibleImmediately() {
        handle.replaceAll(List.of(row("economy", 5, Map.of("coins", 100L))));
        List<RowDiff> diffs = handle.addPending(List.of(new FieldOp.Inc(COINS, 5)));

        assertThat(handle.view("economy")).containsEntry("coins", 105L);
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).before()).containsEntry("coins", 100L);
        assertThat(diffs.get(0).after()).containsEntry("coins", 105L);
        assertThat(handle.pending()).extracting(PendingOp::requestKey).containsExactly((String) null);
    }

    @Test
    void unmarkMakesOpsUnsentInPlace() {
        handle.addPending(List.of(new FieldOp.Inc(COINS, 1), new FieldOp.Inc(COINS, 2)));
        handle.markSent("k1", List.of(new PendingOp(new FieldOp.Inc(COINS, 1), null)));

        handle.unmark("k1");

        assertThat(handle.pending()).extracting(PendingOp::requestKey).containsExactly(null, null);
        assertThat(handle.pending()).extracting(PendingOp::op)
                .containsExactly(new FieldOp.Inc(COINS, 1), new FieldOp.Inc(COINS, 2));
        assertThat(handle.view("economy")).containsEntry("coins", 3L);
    }

    @Test
    void olderVersionIsIgnored() {
        handle.replaceAll(List.of(row("economy", 10, Map.of("coins", 100L))));

        assertThat(handle.accept(event("economy", 10, false, Map.of("coins", 1L), null))).isEmpty();
        assertThat(handle.accept(event("economy", 9, false, Map.of("coins", 2L), null))).isEmpty();

        assertThat(handle.view("economy")).containsEntry("coins", 100L);
        assertThat(handle.version("economy")).isEqualTo(10);
    }

    @Test
    void newerStateReplaysPending() {
        handle.replaceAll(List.of(row("economy", 5, Map.of("coins", 100L))));
        handle.addPending(List.of(new FieldOp.Inc(COINS, 5)));

        handle.accept(event("economy", 9, false, Map.of("coins", 200L), "foreign"));

        assertThat(handle.view("economy")).containsEntry("coins", 205L);
        assertThat(handle.version("economy")).isEqualTo(9);
        assertThat(handle.pending()).hasSize(1);
    }

    @Test
    void echoWithOwnKeyDropsPending() {
        handle.replaceAll(List.of(row("economy", 5, Map.of("coins", 100L))));
        handle.addPending(List.of(new FieldOp.Inc(COINS, 5)));
        handle.markSent("K", handle.pending());

        assertThat(handle.pending()).extracting(PendingOp::requestKey).containsExactly("K");

        List<RowDiff> diffs = handle.accept(event("economy", 6, false, Map.of("coins", 105L), "K"));

        assertThat(handle.view("economy")).containsEntry("coins", 105L);
        assertThat(handle.pending()).isEmpty();
        assertThat(diffs).isEmpty(); // the visible value did not change
    }

    @Test
    void ackAfterEchoIsHarmless() {
        handle.replaceAll(List.of(row("economy", 5, Map.of("coins", 100L))));
        handle.addPending(List.of(new FieldOp.Inc(COINS, 5)));
        handle.markSent("K", handle.pending());
        handle.accept(event("economy", 6, false, Map.of("coins", 105L), "K"));

        List<RowDiff> diffs = handle.acked("K", List.of(row("economy", 6, Map.of("coins", 105L))));

        assertThat(diffs).isEmpty();
        assertThat(handle.view("economy")).containsEntry("coins", 105L);
        assertThat(handle.version("economy")).isEqualTo(6);
        assertThat(handle.pending()).isEmpty();
    }

    @Test
    void deletedRowResetsToDefaults() {
        handle.replaceAll(List.of(row("economy", 5, Map.of("coins", 100L))));

        List<RowDiff> diffs = handle.accept(event("economy", 6, true, Map.of(), null));

        assertThat(handle.view("economy")).isEmpty();
        assertThat(diffs).hasSize(1);
        handle.addPending(List.of(new FieldOp.Inc(COINS, 5)));
        assertThat(handle.view("economy")).containsEntry("coins", 5L); // default 0 + 5
        assertThat(handle.version("economy")).isEqualTo(6);
    }

    @Test
    void rejectedRevertsLocalValue() {
        handle.replaceAll(List.of(row("economy", 5, Map.of("coins", 100L))));
        handle.addPending(List.of(new FieldOp.Inc(COINS, 5)));
        handle.markSent("K", handle.pending());

        List<RowDiff> diffs = handle.rejected("K");

        assertThat(handle.view("economy")).containsEntry("coins", 100L);
        assertThat(handle.pending()).isEmpty();
        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).after()).containsEntry("coins", 100L);
    }

    @Test
    void diffsReportOnlyChangedNamespaces() {
        handle.replaceAll(List.of(
                row("economy", 5, Map.of("coins", 100L)),
                row("lobby", 3, Map.of("level", 4L))));

        List<RowDiff> diffs = handle.addPending(List.of(new FieldOp.Inc(COINS, 5)));
        assertThat(diffs).extracting(RowDiff::namespace).containsExactly("economy");

        diffs = handle.accept(event("lobby", 4, false, Map.of("level", 4L), null));
        assertThat(diffs).isEmpty(); // newer version, same visible content
        assertThat(handle.version("lobby")).isEqualTo(4);

        diffs = handle.accept(event("lobby", 5, false, Map.of("level", 7L), null));
        assertThat(diffs).extracting(RowDiff::namespace).containsExactly("lobby");

        diffs = handle.replaceAll(List.of(
                row("economy", 8, Map.of("coins", 100L)),
                row("lobby", 9, Map.of("level", 7L))));
        assertThat(diffs).isEmpty(); // 100 + pending 5 = 105 as before, lobby unchanged

        diffs = handle.addPending(List.of(new FieldOp.Inc(LEVEL, 1), new FieldOp.Inc(COINS, 1)));
        assertThat(diffs).extracting(RowDiff::namespace).containsExactlyInAnyOrder("economy", "lobby");
    }

    @Test
    void multiNamespaceRequestEchoesDropOnlyTheirNamespace() {
        for (boolean economyFirst : new boolean[] {true, false}) {
            DocHandle h = new DocHandle(new PlayerDoc(PLAYER));
            h.replaceAll(List.of(row("economy", 5, Map.of("coins", 100L)), row("lobby", 3, Map.of("level", 4L))));
            h.addPending(List.of(new FieldOp.Inc(COINS, 5), new FieldOp.Inc(LEVEL, 1)));
            h.markSent("K", h.pending());

            DocChanged eco = event("economy", 6, false, Map.of("coins", 105L), "K");
            DocChanged lobby = event("lobby", 4, false, Map.of("level", 5L), "K");
            List<RowDiff> d1 = h.accept(economyFirst ? eco : lobby);
            assertThat(d1).isEmpty();
            assertThat(h.view("economy")).containsEntry("coins", 105L);
            assertThat(h.view("lobby")).containsEntry("level", 5L);
            assertThat(h.pending()).hasSize(1);

            assertThat(h.accept(economyFirst ? lobby : eco)).isEmpty();
            assertThat(h.pending()).isEmpty();
            assertThat(h.view("economy")).containsEntry("coins", 105L);
            assertThat(h.view("lobby")).containsEntry("level", 5L);
        }
    }

    @Test
    void foreignEventOvertakingOwnEchoEndsCorrect() {
        handle.replaceAll(List.of(row("economy", 5, Map.of("coins", 100L))));
        handle.addPending(List.of(new FieldOp.Inc(COINS, 5)));
        handle.markSent("K", handle.pending());

        handle.accept(event("economy", 7, false, Map.of("coins", 200L), "foreign")); // includes K's effect
        assertThat(handle.view("economy")).containsEntry("coins", 205L); // transient double count
        handle.accept(event("economy", 6, false, Map.of("coins", 105L), "K")); // older, only drops the op

        assertThat(handle.view("economy")).containsEntry("coins", 200L);
        assertThat(handle.version("economy")).isEqualTo(7);
        assertThat(handle.pending()).isEmpty();
    }
}
