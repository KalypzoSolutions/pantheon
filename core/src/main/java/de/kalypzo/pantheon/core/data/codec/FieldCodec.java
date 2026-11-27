package de.kalypzo.pantheon.core.data.codec;

import org.jspecify.annotations.Nullable;

/** Converts between a field's Java value and its wire value. */
public interface FieldCodec<T> {

    /** Encodes a value as a wire value. {@code null} stays {@code null}. */
    @Nullable Object encode(@Nullable T value);

    /** Decodes a wire value. For {@code null} the field's default is returned. */
    @Nullable T decode(@Nullable Object wire);
}
