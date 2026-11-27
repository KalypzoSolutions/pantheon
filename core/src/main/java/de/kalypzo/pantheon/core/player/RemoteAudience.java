package de.kalypzo.pantheon.core.player;

import de.kalypzo.pantheon.core.backend.Delivery;
import de.kalypzo.pantheon.core.backend.DeliveryKind;
import de.kalypzo.pantheon.core.backend.MessageBackend;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.jspecify.annotations.NonNull;

/**
 * A player on another server: what it receives is already rendered and goes through the {@link MessageBackend}
 * (fire-and-forget, in order; E2). Everything but chat, action bar, title and sound is a no-op. Thread safe.
 */
final class RemoteAudience implements Audience {

    private final UUID player;
    private final MessageBackend backend;

    RemoteAudience(UUID player, MessageBackend backend) {
        this.player = Objects.requireNonNull(player, "player");
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public void sendMessage(@NonNull Component message) {
        backend.deliver(player, new Delivery(DeliveryKind.CHAT, message, null, null, null));
    }

    @Override
    public void sendActionBar(@NonNull Component message) {
        backend.deliver(player, new Delivery(DeliveryKind.ACTION_BAR, message, null, null, null));
    }

    @Override
    public void showTitle(@NonNull Title title) {
        backend.deliver(player, new Delivery(DeliveryKind.TITLE, title.title(), title.subtitle(), title.times(), null));
    }

    @Override
    public void playSound(@NonNull Sound sound) {
        backend.deliver(player, new Delivery(DeliveryKind.SOUND, null, null, null, sound));
    }
}
