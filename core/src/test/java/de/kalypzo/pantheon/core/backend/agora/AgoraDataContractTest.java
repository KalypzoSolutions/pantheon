package de.kalypzo.pantheon.core.backend.agora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.ContractFixture;
import de.kalypzo.pantheon.core.backend.DataBackendContract;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The data contract against a real Agora with Postgres (agora.md §2). */
@Tag("integration")
class AgoraDataContractTest extends DataBackendContract {

    @Override
    protected ContractFixture fixture() {
        return new AgoraContractFixture();
    }

    /**
     * R45 against the real Agora: the barrier is acknowledged and a reload right after a release stays holder. The
     * ordering itself is pinned by Agora's {@code HolderRoutingTest.barrierAckFollowsEarlierRelease} (the race is
     * too narrow to provoke reliably here).
     */
    @Test
    void streamBarrierRoundTripsAndReloadStaysHolder() throws Exception {
        try (ContractFixture fx = fixture()) {
            Backend a = fx.node("lobby-01");
            Backend b = fx.node("lobby-02");
            UUID p = UUID.randomUUID();
            List<DocChanged> events = new CopyOnWriteArrayList<>();
            a.onDocChanged(events::add);
            for (int round = 0; round < 5; round++) {
                get(a.loadPlayer(p));
                a.releasePlayer(p);
                get(a.streamBarrier());
                get(a.loadPlayer(p));
            }
            get(b.apply(write(new PlayerDoc(p), inc(COINS, 1))));
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> events.stream().anyMatch(e -> e.ref().equals(new PlayerRow(p, "contract"))));
            assertThat(events).isNotEmpty();
        }
    }
}
