package de.kalypzo.pantheon.velocity;

import de.kalypzo.agora.proto.v1.Deliver;
import de.kalypzo.agora.proto.v1.Delivery;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Hands deliveries from Agora to the player; the proxy holds the connection across server switches. */
public final class ProxyDeliveryHandler {

    private final ProxyPlayers players;
    private final Logger log;

    public ProxyDeliveryHandler(ProxyPlayers players, Logger log) {
        this.players = players;
        this.log = log;
    }

    public void handle(Deliver deliver) {
        try {
            Optional<ProxyPlayer> player = players.find(UUID.fromString(deliver.getPlayer()));
            if (player.isEmpty()) {
                log.debug("Delivery for {} dropped: not on this proxy", deliver.getPlayer());
                return;
            }
            apply(player.get(), deliver.getDelivery());
        } catch (RuntimeException e) {
            log.warn("Delivery for {} could not be handled", deliver.getPlayer(), e);
        }
    }

    private void apply(ProxyPlayer player, Delivery d) {
        switch (d.getKind()) {
            case CHAT -> player.sendMessage(component(d.getComponentJson()));
            case ACTION_BAR -> player.sendActionBar(component(d.getComponentJson()));
            case TITLE -> player.showTitle(title(d));
            case SOUND -> {
                var spec = d.getSound();
                Sound.Source source = Sound.Source.NAMES.value(spec.getSource().toLowerCase(Locale.ROOT));
                Sound sound = Sound.sound(Key.key(spec.getKey()), source == null ? Sound.Source.MASTER : source,
                        spec.getVolume(), spec.getPitch());
                if (!player.playSound(sound)) {
                    log.debug("Sound {} discarded: not supported by this Velocity version", spec.getKey());
                }
            }
            default -> log.debug("Delivery of kind {} ignored", d.getKind());
        }
    }

    private static Component component(String json) {
        return GsonComponentSerializer.gson().deserialize(json);
    }

    private static Title title(Delivery d) {
        Component sub = d.getSubtitleJson().isEmpty() ? Component.empty() : component(d.getSubtitleJson());
        Title.Times times = d.hasTimes()
                ? Title.Times.times(Duration.ofMillis(d.getTimes().getFadeInMillis()),
                Duration.ofMillis(d.getTimes().getStayMillis()), Duration.ofMillis(d.getTimes().getFadeOutMillis()))
                : Title.DEFAULT_TIMES;
        return Title.title(component(d.getComponentJson()), sub, times);
    }
}
