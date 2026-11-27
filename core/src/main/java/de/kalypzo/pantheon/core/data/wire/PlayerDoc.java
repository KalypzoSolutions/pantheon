package de.kalypzo.pantheon.core.data.wire;

import java.util.UUID;

/** The whole data of a player, across namespaces. */
public record PlayerDoc(UUID player) implements DocRef {}
