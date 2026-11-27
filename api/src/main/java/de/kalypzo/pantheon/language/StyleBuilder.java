package de.kalypzo.pantheon.language;

import net.kyori.adventure.sound.Sound;

/** Templates per channel and sound of a message type. Unset parts come from {@link MessageType#DEFAULT}. */
public interface StyleBuilder {

    StyleBuilder chat(String template);

    StyleBuilder actionbar(String template);

    StyleBuilder title(String template);

    StyleBuilder subtitle(String template);

    StyleBuilder sound(Sound sound);

    StyleBuilder noSound();
}
