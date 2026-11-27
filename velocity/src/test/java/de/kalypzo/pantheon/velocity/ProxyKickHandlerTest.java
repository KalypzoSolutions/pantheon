package de.kalypzo.pantheon.velocity;

import de.kalypzo.agora.proto.v1.KickPlayer;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyKickHandlerTest {

    private final FakeProxy.Players players = new FakeProxy.Players();
    private final FakeProxy.Link link = new FakeProxy.Link();
    private final ProxyKickHandler handler = new ProxyKickHandler(players, link);

    @Test
    void kickDisconnectsAndRepliesOnline() {
        UUID uuid = UUID.randomUUID();
        FakeProxy.Player p = players.add(new FakeProxy.Player(uuid, "Alex", "lobby-01"));

        handler.handle(KickPlayer.newBuilder().setRequestId("r1").setPlayer(uuid.toString()).build());

        assertThat(p.disconnected).isEqualTo(ProxyTexts.LOGGED_IN_ELSEWHERE);
        var result = link.sent.getFirst().getKickResult();
        assertThat(result.getRequestId()).isEqualTo("r1");
        assertThat(result.getWasOnline()).isTrue();
    }

    @Test
    void kickForUnknownRepliesNotOnline() {
        handler.handle(KickPlayer.newBuilder().setRequestId("r2").setPlayer(UUID.randomUUID().toString()).build());

        var result = link.sent.getFirst().getKickResult();
        assertThat(result.getRequestId()).isEqualTo("r2");
        assertThat(result.getWasOnline()).isFalse();
    }

    @Test
    void textsAreFixed() {
        assertThat(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(ProxyTexts.LOGGED_IN_ELSEWHERE)).isEqualTo("Von einem anderen Ort angemeldet");
        assertThat(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(ProxyTexts.DATA_UNAVAILABLE))
                .isEqualTo("Die Spielerdaten sind gerade nicht erreichbar. Bitte versuche es gleich noch einmal.");
    }
}
