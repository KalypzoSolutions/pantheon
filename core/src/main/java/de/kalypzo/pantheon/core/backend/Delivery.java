package de.kalypzo.pantheon.core.backend;

import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.jspecify.annotations.Nullable;

/** A fully rendered message for a player. Which fields are set depends on the kind. */
public record Delivery(DeliveryKind kind, @Nullable Component content, @Nullable Component subtitle,
                       Title.@Nullable Times times, @Nullable Sound sound) {}
