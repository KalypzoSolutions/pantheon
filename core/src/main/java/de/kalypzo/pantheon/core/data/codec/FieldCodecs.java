package de.kalypzo.pantheon.core.data.codec;

import de.kalypzo.pantheon.data.Field;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

/** Builds the {@link FieldCodec} of a {@link Field}. */
public final class FieldCodecs {

    private FieldCodecs() {}

    /** @throws IllegalArgumentException if the field's type (e.g. a record component) is not supported */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> FieldCodec<T> forField(Field<T> field) {
        FieldCodec<Object> inner = switch (field.type()) {
            case INT -> RecordCodec.codecFor(Integer.class);
            case LONG -> RecordCodec.codecFor(Long.class);
            case DOUBLE -> RecordCodec.codecFor(Double.class);
            case BOOLEAN -> RecordCodec.codecFor(Boolean.class);
            case STRING -> RecordCodec.codecFor(String.class);
            case UUID -> RecordCodec.codecFor(java.util.UUID.class);
            case INSTANT -> RecordCodec.codecFor(java.time.Instant.class);
            case ENUM, OBJECT -> RecordCodec.codecFor(field.valueClass());
            case SET -> collectionCodec(field, true);
            case LIST -> collectionCodec(field, false);
        };
        return new WithDefault<>((FieldCodec) inner, field.defaultValue());
    }

    private static FieldCodec<Object> collectionCodec(Field<?> field, boolean set) {
        Class<?> element = field.elementClass();
        if (element == null) {
            throw new IllegalArgumentException("Field " + field + " has no element class");
        }
        java.lang.reflect.Type type = new java.lang.reflect.ParameterizedType() {
            @Override
            public java.lang.reflect.Type[] getActualTypeArguments() {
                return new java.lang.reflect.Type[] {element};
            }

            @Override
            public java.lang.reflect.Type getRawType() {
                return set ? java.util.Set.class : java.util.List.class;
            }

            @Override
            public java.lang.reflect.Type getOwnerType() {
                return null;
            }
        };
        return RecordCodec.codecFor(type);
    }

    private record WithDefault<T>(FieldCodec<T> inner, @Nullable T defaultValue) implements FieldCodec<T> {
        @Override
        public @Nullable Object encode(@Nullable T value) {
            return inner.encode(value);
        }

        @Override
        public @Nullable T decode(@Nullable Object wire) {
            T v = inner.decode(wire);
            return v != null ? v : defaultValue;
        }
    }
}
