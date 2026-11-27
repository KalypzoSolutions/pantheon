package de.kalypzo.pantheon.velocity;

import de.kalypzo.agora.proto.v1.ClaimPresenceResponse;
import de.kalypzo.agora.proto.v1.Heartbeat;
import de.kalypzo.agora.proto.v1.Hello;
import de.kalypzo.agora.proto.v1.NodeKind;
import de.kalypzo.agora.proto.v1.NodeMessage;
import de.kalypzo.agora.proto.v1.PresenceEntry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyPresenceReporterTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    private final FakeProxy.Players players = new FakeProxy.Players();
    private final FakeProxy.Link link = new FakeProxy.Link();
    private final ProxyPresenceReporter reporter =
            new ProxyPresenceReporter("proxy-01", players, link, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID uuid = UUID.randomUUID();
    private final Object session = new Object();

    @Test
    void loginWaitsForClaim() {
        CompletableFuture<ClaimPresenceResponse> pending = new CompletableFuture<>();
        link.nextClaim = pending;

        CompletableFuture<Boolean> login = reporter.login(session, uuid, "Alex", "de_DE");

        assertThat(login).isNotDone();
        PresenceEntry entry = link.claims.getFirst().getEntry();
        assertThat(entry.getPlayer()).isEqualTo(uuid.toString());
        assertThat(entry.getName()).isEqualTo("Alex");
        assertThat(entry.getServer()).isEmpty();
        assertThat(entry.getProxy()).isEqualTo("proxy-01");
        assertThat(entry.getLocale()).isEqualTo("de_DE");
        assertThat(entry.getOnlineSinceMillis()).isEqualTo(NOW.toEpochMilli());

        pending.complete(ClaimPresenceResponse.newBuilder().setAccepted(true).build());
        assertThat(login).isCompletedWithValue(true);
    }

    @Test
    void claimFailureDeniesLogin() {
        link.nextClaim = CompletableFuture.failedFuture(new IllegalStateException("agora down"));
        assertThat(reporter.login(session, uuid, "Alex", "")).isCompletedWithValue(false);

        link.nextClaim = CompletableFuture.completedFuture(ClaimPresenceResponse.newBuilder().setAccepted(false).build());
        assertThat(reporter.login(new Object(), uuid, "Alex", "")).isCompletedWithValue(false);

        // a denied login never reports anything for the player
        reporter.left(session, uuid);
        assertThat(link.sent).isEmpty();
    }

    @Test
    void switchReported() {
        reporter.login(session, uuid, "Alex", "de_DE");
        reporter.switched(session, uuid, "lobby-01");

        NodeMessage m = link.sent.getFirst();
        assertThat(m.getPlayerSwitched().getPlayer()).isEqualTo(uuid.toString());
        assertThat(m.getPlayerSwitched().getServer()).isEqualTo("lobby-01");
    }

    @Test
    void disconnectReported() {
        reporter.login(session, uuid, "Alex", "de_DE");
        reporter.left(session, uuid);

        assertThat(link.sent.getFirst().getPlayerLeft().getPlayer()).isEqualTo(uuid.toString());
    }

    @Test
    void staleSessionDoesNotReport() {
        reporter.login(session, uuid, "Alex", "de_DE");
        Object newer = new Object();
        reporter.login(newer, uuid, "Alex", "de_DE");

        reporter.left(session, uuid); // the old connection ends after the new login
        assertThat(link.sent).isEmpty();
        reporter.left(newer, uuid);
        assertThat(link.sent).hasSize(1);
    }

    @Test
    void heartbeatListsAllPlayersWithServer() {
        UUID other = UUID.randomUUID();
        players.add(new FakeProxy.Player(uuid, "Alex", "lobby-01"));
        players.add(new FakeProxy.Player(other, "Steve", null));

        Hello hello = reporter.state().hello();
        Heartbeat heartbeat = reporter.state().heartbeat();

        assertThat(hello.getNodeId()).isEqualTo("proxy-01");
        assertThat(hello.getKind()).isEqualTo(NodeKind.VELOCITY);
        for (var entries : new java.util.List[]{hello.getPlayersList(), heartbeat.getPlayersList()}) {
            @SuppressWarnings("unchecked")
            java.util.List<PresenceEntry> list = entries;
            assertThat(list).extracting(PresenceEntry::getPlayer).containsExactlyInAnyOrder(uuid.toString(), other.toString());
            assertThat(list).filteredOn(e -> e.getPlayer().equals(uuid.toString()))
                    .singleElement().satisfies(e -> {
                        assertThat(e.getServer()).isEqualTo("lobby-01");
                        assertThat(e.getName()).isEqualTo("Alex");
                        assertThat(e.getProxy()).isEqualTo("proxy-01");
                    });
            assertThat(list).filteredOn(e -> e.getPlayer().equals(other.toString()))
                    .singleElement().satisfies(e -> assertThat(e.getServer()).isEmpty());
        }
    }
}
