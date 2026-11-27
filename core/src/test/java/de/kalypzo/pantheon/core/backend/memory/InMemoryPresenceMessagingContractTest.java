package de.kalypzo.pantheon.core.backend.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import de.kalypzo.pantheon.core.backend.ContractFixture;
import de.kalypzo.pantheon.core.backend.Delivery;
import de.kalypzo.pantheon.core.backend.DeliveryKind;
import de.kalypzo.pantheon.core.backend.PresenceChange;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.backend.PresenceMessagingContract;
import de.kalypzo.pantheon.player.ExpectedOnlinePlayerException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class InMemoryPresenceMessagingContractTest extends PresenceMessagingContract {

    @Override
    protected ContractFixture fixture() {
        return new InMemoryContractFixture();
    }

    /** E1: Agora waits at most 3 s for the server of the player; the in-memory network does the same. */
    @Test
    void locationWaitsAtMost3SecondsForTheServer() {
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            InMemoryNetwork.Node server = network.node("lobby-01");
            InMemoryNetwork.Node asker = network.node("lobby-02");
            UUID p = UUID.randomUUID();
            server.answerLocations(player -> new CompletableFuture<>()); // never answers
            network.join(entry(p));
            network.switchServer(p, "lobby-01");

            long start = System.nanoTime();
            Throwable failure = catchThrowable(() -> asker.location(p).get(10, TimeUnit.SECONDS));
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(failure).isInstanceOf(ExecutionException.class).cause()
                    .isInstanceOfSatisfying(ExpectedOnlinePlayerException.class,
                            e -> assertThat(e.scope()).isEqualTo(ExpectedOnlinePlayerException.Scope.NETWORK));
            assertThat(took).isBetween(Duration.ofMillis(2900), Duration.ofSeconds(5));
        }
    }

    @Test
    void locationFailsWhenResolverThrows() {
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            InMemoryNetwork.Node server = network.node("lobby-01");
            InMemoryNetwork.Node asker = network.node("lobby-02");
            UUID p = UUID.randomUUID();
            server.answerLocations(player -> {
                throw new IllegalStateException("boom");
            });
            network.join(entry(p));
            network.switchServer(p, "lobby-01");

            Throwable failure = catchThrowable(() -> asker.location(p).get(5, TimeUnit.SECONDS));

            assertThat(failure).isInstanceOf(ExecutionException.class).cause()
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void deliveryDuringOutageIsLost() {
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            InMemoryNetwork.Node node = network.node("lobby-01");
            UUID p = UUID.randomUUID();
            network.join(entry(p));

            network.outage();
            node.deliver(p, new Delivery(DeliveryKind.CHAT, Component.text("x"), null, null, null));
            network.restore();
            node.deliver(p, new Delivery(DeliveryKind.CHAT, Component.text("marker"), null, null, null));

            assertThat(network.deliveries("proxy-01")).singleElement()
                    .satisfies(d -> assertThat(d.delivery().content()).isEqualTo(Component.text("marker")));
        }
    }

    @Test
    void presenceChangedDuringOutageArrivesAsReplacedOnRestore() {
        try (InMemoryNetwork network = new InMemoryNetwork()) {
            InMemoryNetwork.Node node = network.node("lobby-01");
            UUID p = UUID.randomUUID();
            List<PresenceChange> seen = new CopyOnWriteArrayList<>();
            node.onPresenceChanged(seen::add);

            network.outage();
            network.join(entry(p));
            assertThat(seen).isEmpty();
            network.restore();

            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(seen).singleElement()
                    .isInstanceOfSatisfying(PresenceChange.Replaced.class,
                            r -> assertThat(r.all()).extracting(PresenceEntry::player).contains(p)));
        }
    }

    private static PresenceEntry entry(UUID p) {
        return new PresenceEntry(p, "Test", null, "proxy-01", Locale.GERMANY, Instant.now());
    }
}
