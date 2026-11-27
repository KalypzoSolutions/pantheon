package de.kalypzo.pantheon.core.language;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.jspecify.annotations.NonNull;

/** An Audience that records everything it receives, for tests without a server. Thread safe. */
public final class RecordingAudience implements Audience {

    private final List<Component> messages = new CopyOnWriteArrayList<>();
    private final List<Component> actionbars = new CopyOnWriteArrayList<>();
    private final List<Title> titles = new CopyOnWriteArrayList<>();
    private final List<Sound> sounds = new CopyOnWriteArrayList<>();

    @Override
    public void sendMessage(@NonNull Component message) {
        messages.add(message);
    }

    @Override
    public void sendActionBar(@NonNull Component message) {
        actionbars.add(message);
    }

    @Override
    public void showTitle(@NonNull Title title) {
        titles.add(title);
    }

    @Override
    public void playSound(@NonNull Sound sound) {
        sounds.add(sound);
    }

    public List<Component> messages() {
        return List.copyOf(messages);
    }

    public List<Component> actionbars() {
        return List.copyOf(actionbars);
    }

    public List<Title> titles() {
        return List.copyOf(titles);
    }

    public List<Sound> sounds() {
        return List.copyOf(sounds);
    }
}
