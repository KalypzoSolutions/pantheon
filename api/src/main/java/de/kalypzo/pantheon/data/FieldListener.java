package de.kalypzo.pantheon.data;

import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface FieldListener<T> {

    void changed(Doc doc, @Nullable T before, @Nullable T after);
}
