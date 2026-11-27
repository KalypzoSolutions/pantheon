package de.kalypzo.pantheon.core.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.player.ExpectedOnlinePlayerException;
import de.kalypzo.pantheon.world.NetworkLocation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The presence and messaging contract (agora.md §2, §4, §6): every {@link PresenceBackend} and
 * {@link MessageBackend} implementation must pass it, the in-memory network as well as a real Agora. Proxy actions
 * (join, switch, leave, what a proxy received) go through the {@link ContractFixture}, so a real Agora can drive them
 * through a simulated Velocity node. Fixtures must provide a network without warm-up delay (E11: PAPER nodes get
 * {@code Welcome} 15 s after an Agora start; the Agora fixture sets {@code paperWelcomeDelay} to 0), because the waits
 * here are 10 s. Every test uses fresh players, so it also runs against a shared network.
 */
public abstract class PresenceMessagingContract {

    protected static final String PROXY = "proxy-01";

    private static final Duration WAIT = Duration.ofSeconds(10);

    private ContractFixture fx;
    private Backend a;
    private Backend b;

    /** A new, empty-enough network; called once per test and closed after it. */
    protected abstract ContractFixture fixture();

    @BeforeEach
    void setUpContract() {
        fx = fixture();
        a = fx.node("lobby-01");
        b = fx.node("lobby-02");
    }

    @AfterEach
    void tearDownContract() {
        fx.close();
    }

    @Test
    void welcomeSnapshotContainsOnlinePlayers() {
        UUID p = UUID.randomUUID();
        fx.join(entry(p, "Alice", null));
        fx.switchServer(p, "lobby-01");

        Backend late = fx.node("lobby-03");

        await().atMost(WAIT).untilAsserted(
                () -> assertThat(find(late, p).map(PresenceEntry::server)).contains("lobby-01"));
        PresenceEntry e = find(late, p).orElseThrow();
        assertThat(e.name()).isEqualTo("Alice");
        assertThat(e.proxy()).isEqualTo(PROXY);
        assertThat(e.locale()).isEqualTo(Locale.GERMANY);
    }

    @Test
    void presenceChangesReachAllNodes() {
        UUID p = UUID.randomUUID();
        List<PresenceChange> onA = new CopyOnWriteArrayList<>();
        List<PresenceChange> onB = new CopyOnWriteArrayList<>();
        a.onPresenceChanged(onA::add);
        b.onPresenceChanged(onB::add);

        fx.join(entry(p, "Bob", null));

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(upserts(onA, p)).isNotEmpty();
            assertThat(upserts(onB, p)).isNotEmpty();
            assertThat(find(a, p)).isPresent();
            assertThat(find(b, p)).isPresent();
        });
        assertThat(upserts(onA, p).get(0).name()).isEqualTo("Bob");
    }

    @Test
    void switchUpdatesServer() {
        UUID p = UUID.randomUUID();
        fx.join(entry(p, "Carol", null));
        await().atMost(WAIT).untilAsserted(() -> assertThat(find(a, p)).isPresent());
        assertThat(find(a, p).orElseThrow().server()).isNull();

        fx.switchServer(p, "lobby-02");

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(find(a, p).orElseThrow().server()).isEqualTo("lobby-02");
            assertThat(find(b, p).orElseThrow().server()).isEqualTo("lobby-02");
        });
    }

    @Test
    void leaveRemoves() {
        UUID p = UUID.randomUUID();
        List<PresenceChange> onA = new CopyOnWriteArrayList<>();
        a.onPresenceChanged(onA::add);
        fx.join(entry(p, "Dave", null));
        await().atMost(WAIT).untilAsserted(() -> assertThat(find(a, p)).isPresent());

        fx.leave(p);

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(find(a, p)).isEmpty();
            assertThat(find(b, p)).isEmpty();
        });
        assertThat(onA).anyMatch(c -> c instanceof PresenceChange.Removed r && r.player().equals(p));
    }

    @Test
    void deliveryReachesProxyOfPlayer() {
        UUID p = UUID.randomUUID();
        fx.join(entry(p, "Erin", "lobby-01"));
        await().atMost(WAIT).untilAsserted(() -> assertThat(find(a, p)).isPresent());
        Delivery first = chat("one");
        Delivery second = chat("two");

        a.deliver(p, first);
        a.deliver(p, second);

        await().atMost(WAIT).untilAsserted(() -> assertThat(forPlayer(p)).hasSize(2));
        assertThat(forPlayer(p)).containsExactly(new Delivered(p, first), new Delivered(p, second));
    }

    @Test
    void deliveryToOfflineIsDropped() {
        UUID offline = UUID.randomUUID();
        UUID online = UUID.randomUUID();
        fx.join(entry(online, "Frank", null));
        await().atMost(WAIT).untilAsserted(() -> assertThat(find(a, online)).isPresent());

        a.deliver(offline, chat("lost"));
        // barrier: the stream is ordered, so once the later delivery arrived the earlier one was handled
        a.deliver(online, chat("kept"));

        await().atMost(WAIT).untilAsserted(() -> assertThat(forPlayer(online)).hasSize(1));
        assertThat(forPlayer(offline)).isEmpty();
    }

    @Test
    void locationAnsweredByServerNode() throws Exception {
        UUID p = UUID.randomUUID();
        NetworkLocation where = new NetworkLocation("lobby-01", "world", 1.5, 64, -3, 90, 10);
        a.answerLocations(player -> CompletableFuture.completedFuture(
                player.equals(p) ? Optional.of(where) : Optional.empty()));
        fx.join(entry(p, "Grace", null));
        fx.switchServer(p, "lobby-01");
        await().atMost(WAIT).untilAsserted(() -> assertThat(find(b, p).map(PresenceEntry::server)).contains("lobby-01"));

        // the resolver travels on A's stream and may not be registered yet: ask until answered
        NetworkLocation[] found = new NetworkLocation[1];
        // each attempt may take up to 3 s (E1); Awaitility only retries on exceptions when told to
        await().atMost(Duration.ofSeconds(20)).pollDelay(Duration.ZERO).ignoreExceptions()
                .untilAsserted(() -> found[0] = b.location(p).get(5, TimeUnit.SECONDS));

        assertThat(found[0]).isEqualTo(where);
    }

    @Test
    void locationOfOfflinePlayerFails() {
        UUID p = UUID.randomUUID();

        Throwable failure = catchThrowable(() -> b.location(p).get(5, TimeUnit.SECONDS));

        assertThat(failure).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(ExpectedOnlinePlayerException.class,
                        e -> {
                            assertThat(e.scope()).isEqualTo(ExpectedOnlinePlayerException.Scope.NETWORK);
                            assertThat(e.uuid()).isEqualTo(p);
                        });
    }

    @Test
    void connectionStateEventsOnOutageAndRestore() {
        List<ConnectionState> states = new CopyOnWriteArrayList<>();
        a.onConnectionState(states::add);
        await().atMost(WAIT).until(() -> a.state() == ConnectionState.CONNECTED);

        fx.outage();
        await().atMost(WAIT).until(() -> a.state() == ConnectionState.DISCONNECTED);
        fx.restore();
        await().atMost(WAIT).until(() -> a.state() == ConnectionState.CONNECTED);

        assertThat(states).contains(ConnectionState.DISCONNECTED, ConnectionState.CONNECTED);
        assertThat(states.indexOf(ConnectionState.DISCONNECTED))
                .isLessThan(states.lastIndexOf(ConnectionState.CONNECTED));
    }

    // ---------- helpers ----------

    private static PresenceEntry entry(UUID player, String name, String server) {
        return new PresenceEntry(player, name, server, PROXY, Locale.GERMANY, Instant.now());
    }

    private static Delivery chat(String text) {
        return new Delivery(DeliveryKind.CHAT, Component.text(text), null, null, null);
    }

    private static Optional<PresenceEntry> find(Backend node, UUID player) {
        return node.snapshot().stream().filter(e -> e.player().equals(player)).findFirst();
    }

    private static List<PresenceEntry> upserts(List<PresenceChange> changes, UUID player) {
        return changes.stream()
                .flatMap(c -> switch (c) {
                    case PresenceChange.Upsert u -> java.util.stream.Stream.of(u.e());
                    case PresenceChange.Replaced r -> r.all().stream();
                    case PresenceChange.Removed ignored -> java.util.stream.Stream.<PresenceEntry>empty();
                })
                .filter(e -> e.player().equals(player))
                .toList();
    }

    private List<Delivered> forPlayer(UUID player) {
        return fx.deliveries(PROXY).stream().filter(d -> d.player().equals(player)).toList();
    }
}
