package de.kalypzo.pantheon.core.backend.memory;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.core.backend.ConnectStatus;
import de.kalypzo.pantheon.core.backend.ContractFixture;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.backend.RecordingArrivals;
import de.kalypzo.pantheon.core.backend.TeleportContract;
import de.kalypzo.pantheon.world.NetworkLocation;
import de.kalypzo.pantheon.world.TeleportResult;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The teleport contract against the in-memory network, the proxy step through {@code proxyConnects}. */
class InMemoryTeleportContractTest extends TeleportContract {

    @Override
    protected ContractFixture fixture() {
        return new InMemoryContractFixture();
    }

    @Test
    void withoutProxyIsUnavailable() throws Exception {
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            RecordingArrivals arrivals = new RecordingArrivals();
            network.node("lobby-02").answerArrivals(arrivals);

            assertThat(teleport(network)).isEqualTo(TeleportResult.SERVER_UNAVAILABLE);
            assertThat(arrivals.prepared).isEmpty();
        }
    }

    /** A throwing handler is UNSPECIFIED at once, not a TIMEOUT. */
    @Test
    void throwingPrepareIsUnavailable() throws Exception {
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            RecordingArrivals arrivals = new RecordingArrivals();
            arrivals.answer = pr -> {
                throw new IllegalStateException("boom");
            };
            network.node("lobby-02").answerArrivals(arrivals);
            network.proxyConnects((pl, s) -> CompletableFuture.completedFuture(ConnectStatus.CONNECTED));

            assertThat(teleport(network)).isEqualTo(TeleportResult.SERVER_UNAVAILABLE);
            assertThat(arrivals.cancelled.poll(1, TimeUnit.SECONDS)).isEqualTo(arrivals.nextPrepared().requestId());
        }
    }

    @Test
    void throwingConnectorIsConnectFailed() throws Exception {
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            RecordingArrivals arrivals = new RecordingArrivals();
            network.node("lobby-02").answerArrivals(arrivals);
            network.proxyConnects((pl, s) -> {
                throw new IllegalStateException("boom");
            });

            assertThat(teleport(network)).isEqualTo(TeleportResult.CONNECT_FAILED);
        }
    }

    /** A fresh player on {@code lobby-01}, teleported by {@code lobby-01} to {@code lobby-02}. */
    private static TeleportResult teleport(InMemoryNetwork network) throws Exception {
        UUID p = UUID.randomUUID();
        network.join(new PresenceEntry(p, "Test", "lobby-01", PROXY, Locale.GERMANY, Instant.now()));
        return network.node("lobby-01").teleport(p, "lobby-02", new NetworkLocation("lobby-02", "world", 1, 64, -3,
                90, 10)).get(2, TimeUnit.SECONDS);
    }
}
