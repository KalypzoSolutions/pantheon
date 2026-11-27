package de.kalypzo.pantheon.core.backend;

import java.util.UUID;

/** A delivery as a proxy received it. */
public record Delivered(UUID player, Delivery delivery) {}
