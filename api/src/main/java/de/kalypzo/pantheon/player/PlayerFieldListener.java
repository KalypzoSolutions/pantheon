package de.kalypzo.pantheon.player;

import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface PlayerFieldListener<T> {

    void changed(LocalPanPlayer player, @Nullable T before, @Nullable T after);
}
