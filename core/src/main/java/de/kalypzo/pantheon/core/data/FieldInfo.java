package de.kalypzo.pantheon.core.data;

import de.kalypzo.pantheon.core.data.codec.FieldCodec;
import de.kalypzo.pantheon.core.data.codec.FieldCodecs;
import de.kalypzo.pantheon.core.data.codec.FieldSpecs;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.data.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * A field together with its wire description and codec. Codecs are rebuilt on every {@code FieldCodecs.forField}, so
 * they are cached here, by qualified name inside a {@link ClassValue} of the field's element or value class.
 */
public final class FieldInfo<T> {

    /**
     * Per class the field's own types hang on (element or value class): the cache then lives and dies with the class
     * of a plugin's record or enum and never pins a reloaded plugin's ClassLoader.
     */
    private static final ClassValue<Map<String, FieldInfo<?>>> CACHE = new ClassValue<>() {
        @Override
        protected Map<String, FieldInfo<?>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private final Field<T> field;
    private final FieldSpec spec;
    private final FieldCodec<T> codec;

    private FieldInfo(Field<T> field) {
        this.field = field;
        this.spec = FieldSpecs.of(field);
        this.codec = FieldCodecs.forField(field);
    }

    @SuppressWarnings("unchecked")
    public static <T> FieldInfo<T> of(Field<T> field) {
        Class<?> anchor = field.elementClass() != null ? field.elementClass() : field.valueClass();
        Map<String, FieldInfo<?>> cache = CACHE.get(anchor);
        String key = field.toString();
        FieldInfo<?> cached = cache.get(key);
        if (cached != null && cached.field == field) return (FieldInfo<T>) cached;
        FieldInfo<T> created = new FieldInfo<>(field);
        cache.put(key, created);
        return created;
    }

    public Field<T> field() {
        return field;
    }

    public FieldSpec spec() {
        return spec;
    }

    public @Nullable Object encode(@Nullable T value) {
        return codec.encode(value);
    }

    /** The wire value under the field's name, or under its aliases ({@code readFrom}) while the name is missing. */
    public @Nullable Object raw(Map<String, Object> data) {
        Object value = data.get(field.name());
        if (value != null) return value;
        for (String alias : field.aliases()) {
            value = data.get(alias);
            if (value != null) return value;
        }
        return null;
    }

    /** The value in {@code data}, the field's default if it is not set. */
    public @Nullable T decode(Map<String, Object> data) {
        return codec.decode(raw(data));
    }

    public @Nullable T decodeRaw(@Nullable Object wire) {
        return codec.decode(wire);
    }

    /** Encodes one element of a SET or LIST field. */
    public @Nullable Object encodeElement(Object element) {
        if (element == null) throw new NullPointerException("element");
        @SuppressWarnings("unchecked")
        FieldCodec<Object> c = (FieldCodec<Object>) codec;
        Object wire = switch (field.type()) {
            case SET -> c.encode(Set.of(element));
            case LIST -> c.encode(List.of(element));
            default -> throw new IllegalArgumentException("Field " + field + " is not a set or list");
        };
        return ((List<?>) wire).getFirst();
    }
}
