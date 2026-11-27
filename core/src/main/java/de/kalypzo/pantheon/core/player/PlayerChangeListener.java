package de.kalypzo.pantheon.core.player;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A field of a player held on this node changed; paper maps it to {@code PlayerFieldListener}. */
@FunctionalInterface
public interface PlayerChangeListener<T> {

    void changed(UUID player, @Nullable T before, @Nullable T after);
}
