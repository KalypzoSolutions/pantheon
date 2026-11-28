package de.kalypzo.pantheon.velocity;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status;
import de.kalypzo.agora.proto.v1.ConnectPlayer;
import de.kalypzo.agora.proto.v1.ConnectStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyConnectHandlerTest {

    private final FakeProxy.Players players = new FakeProxy.Players();
    private final FakeProxy.Link link = new FakeProxy.Link();
    private final ProxyConnectHandler handler = new ProxyConnectHandler(players, link);

    private ConnectPlayer request(String player) {
        return ConnectPlayer.newBuilder().setRequestId("r1").setPlayer(player).setServer("lobby-02").build();
    }

    private void assertReply(ConnectStatus expected) {
        assertThat(link.sent).hasSize(1);
        var result = link.sent.getFirst().getConnectResult();
        assertThat(result.getRequestId()).isEqualTo("r1");
        assertThat(result.getStatus()).isEqualTo(expected);
    }

    @Test
    void connectsAndReports() {
        UUID uuid = UUID.randomUUID();
        FakeProxy.Player p = players.add(new FakeProxy.Player(uuid, "Alex", "lobby-01"));

        handler.handle(request(uuid.toString()));

        assertThat(p.connectedTo).isEqualTo("lobby-02");
        assertReply(ConnectStatus.CONNECTED);
    }

    @Test
    void unknownPlayerIsNotFound() {
        handler.handle(request(UUID.randomUUID().toString()));
        assertReply(ConnectStatus.PLAYER_NOT_FOUND);
    }

    @Test
    void malformedUuidIsNotFound() {
        handler.handle(request("not-a-uuid"));
        assertReply(ConnectStatus.PLAYER_NOT_FOUND);
    }

    @Test
    void failedFutureIsFailed() {
        UUID uuid = UUID.randomUUID();
        FakeProxy.Player p = players.add(new FakeProxy.Player(uuid, "Alex", "lobby-01"));
        p.connectResult = CompletableFuture.failedFuture(new IllegalStateException("boom"));

        handler.handle(request(uuid.toString()));

        assertReply(ConnectStatus.FAILED);
    }

    @Test
    void mapsVelocityStatus() {
        assertThat(ProxyConnectHandler.fromVelocity(Status.SUCCESS)).isEqualTo(ConnectStatus.CONNECTED);
        assertThat(ProxyConnectHandler.fromVelocity(Status.ALREADY_CONNECTED)).isEqualTo(ConnectStatus.ALREADY_CONNECTED);
        assertThat(ProxyConnectHandler.fromVelocity(Status.CONNECTION_IN_PROGRESS)).isEqualTo(ConnectStatus.FAILED);
        assertThat(ProxyConnectHandler.fromVelocity(Status.CONNECTION_CANCELLED)).isEqualTo(ConnectStatus.FAILED);
        assertThat(ProxyConnectHandler.fromVelocity(Status.SERVER_DISCONNECTED)).isEqualTo(ConnectStatus.FAILED);
    }
}
