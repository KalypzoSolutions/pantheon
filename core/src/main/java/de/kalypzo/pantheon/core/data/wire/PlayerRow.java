package de.kalypzo.pantheon.core.data.wire;

import java.util.UUID;

/** One namespace row of a player. */
public record PlayerRow(UUID player, String namespace) implements RowRef {}
