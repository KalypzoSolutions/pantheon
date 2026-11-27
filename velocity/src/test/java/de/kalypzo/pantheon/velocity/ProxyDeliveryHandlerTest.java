package de.kalypzo.pantheon.velocity;

import de.kalypzo.agora.proto.v1.Deliver;
import de.kalypzo.agora.proto.v1.Delivery;
import de.kalypzo.agora.proto.v1.DeliveryKind;
import de.kalypzo.agora.proto.v1.SoundSpec;
import de.kalypzo.agora.proto.v1.TitleTimes;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyDeliveryHandlerTest {

    private final FakeProxy.Players players = new FakeProxy.Players();
    private final ProxyDeliveryHandler handler = new ProxyDeliveryHandler(players, NOPLogger.NOP_LOGGER);
    private final UUID uuid = UUID.randomUUID();
    private final FakeProxy.Player player = players.add(new FakeProxy.Player(uuid, "Alex", "lobby-01"));

    private static String json(Component c) {
        return GsonComponentSerializer.gson().serialize(c);
    }

    private void deliver(Delivery d) {
        handler.handle(Deliver.newBuilder().setPlayer(uuid.toString()).setDelivery(d).build());
    }

    @Test
    void deliverChatActionbarTitle() {
        Component hello = Component.text("Hallo");
        deliver(Delivery.newBuilder().setKind(DeliveryKind.CHAT).setComponentJson(json(hello)).build());
        deliver(Delivery.newBuilder().setKind(DeliveryKind.ACTION_BAR).setComponentJson(json(hello)).build());
        deliver(Delivery.newBuilder().setKind(DeliveryKind.TITLE).setComponentJson(json(hello))
                .setSubtitleJson(json(Component.text("Sub")))
                .setTimes(TitleTimes.newBuilder().setFadeInMillis(100).setStayMillis(2000).setFadeOutMillis(300)).build());

        assertThat(player.received).hasSize(3);
        assertThat(player.received.get(0)).isEqualTo(hello);
        assertThat(player.received.get(1)).isEqualTo(Map.entry("actionbar", hello));
        Title title = (Title) player.received.get(2);
        assertThat(title.title()).isEqualTo(hello);
        assertThat(title.subtitle()).isEqualTo(Component.text("Sub"));
        assertThat(title.times()).isEqualTo(Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(300)));
    }

    @Test
    void soundIsPlayedOrDiscarded() {
        Delivery sound = Delivery.newBuilder().setKind(DeliveryKind.SOUND).setSound(SoundSpec.newBuilder()
                .setKey("block.note_block.pling").setSource("MASTER").setVolume(1).setPitch(1)).build();
        deliver(sound);
        assertThat(player.received).singleElement().isInstanceOf(Sound.class);

        player.received.clear();
        player.soundSupported = false;
        deliver(sound); // discarded, no exception
        assertThat(player.received).isEmpty();
    }

    @Test
    void unknownPlayerOrBrokenPayloadIsIgnored() {
        handler.handle(Deliver.newBuilder().setPlayer(UUID.randomUUID().toString())
                .setDelivery(Delivery.newBuilder().setKind(DeliveryKind.CHAT).setComponentJson(json(Component.text("x")))).build());
        deliver(Delivery.newBuilder().setKind(DeliveryKind.CHAT).setComponentJson("{not json").build());
        assertThat(player.received).isEmpty();
    }
}
