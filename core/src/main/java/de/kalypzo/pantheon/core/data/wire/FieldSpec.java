package de.kalypzo.pantheon.core.data.wire;

import org.jspecify.annotations.Nullable;

/** A field as the backend sees it: namespace, name, wire type and the default as a wire value. */
public record FieldSpec(String namespace, String name, ScalarType type, @Nullable Object defaultValue) {
    public FieldSpec {
        defaultValue = Wire.immutable(defaultValue);
    }
}
