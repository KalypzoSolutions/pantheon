package de.kalypzo.pantheon.core.audit;

import java.util.Objects;

/** World and coordinates of an entry. Coordinates must be finite (Agora drops NaN/Infinity). */
public record Position(String world, double x, double y, double z) {

    public Position {
        Objects.requireNonNull(world, "world");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("coordinates must be finite");
        }
    }
}
