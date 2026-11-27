package de.kalypzo.pantheon.core.audit;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Who reads a timeline. {@code player} is null for "no player" (the backend maps it to the nil UUID, E37). */
public record Viewer(@Nullable UUID player, String name, String via) {}
