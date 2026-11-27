package de.kalypzo.pantheon.core.data.codec;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Encodes a record as a wire map, one entry per record component. Supported component types: primitives and their
 * wrappers, String, UUID, enums, Instant, nested records, and List, Set and {@code Map<String, ...>} of those.
 */
public final class RecordCodec<R extends Record> implements FieldCodec<R> {

    private final Class<R> type;
    private final RecordComponent[] components;
    private final FieldCodec<Object>[] codecs;

    @SuppressWarnings("unchecked")
    private RecordCodec(Class<R> type) {
        this.type = type;
        this.components = type.getRecordComponents();
        this.codecs = new FieldCodec[components.length];
        for (int i = 0; i < components.length; i++) {
            try {
                codecs[i] = codecFor(components[i].getGenericType());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unsupported component '" + components[i].getName() + "' of "
                        + type.getName() + ": " + e.getMessage(), e);
            }
        }
    }

    /** @throws IllegalArgumentException if a component type is not supported */
    public static <R extends Record> RecordCodec<R> of(Class<R> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type.getName() + " is not a record");
        }
        return new RecordCodec<>(type);
    }

    @Override
    public @Nullable Object encode(@Nullable R value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < components.length; i++) {
            try {
                map.put(components[i].getName(), codecs[i].encode(components[i].getAccessor().invoke(value)));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read " + components[i].getName() + " of " + type.getName(), e);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    @Override
    public @Nullable R decode(@Nullable Object wire) {
        if (!(wire instanceof Map<?, ?> map)) {
            return null;
        }
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            args[i] = codecs[i].decode(map.get(components[i].getName()));
        }
        try {
            var ctor = type.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Cannot construct " + type.getName(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot construct " + type.getName(), e);
        }
    }

    // ---- type codecs: decode(null) yields null, or the zero value for primitives ----

    @SuppressWarnings({"unchecked", "rawtypes"})
    static FieldCodec<Object> codecFor(Type t) {
        if (t instanceof Class<?> c) {
            if (c == int.class || c == Integer.class) {
                return number(c.isPrimitive(), (Object) 0, n -> (int) Math.clamp(n.longValue(), Integer.MIN_VALUE, Integer.MAX_VALUE), true);
            }
            if (c == short.class || c == Short.class) {
                return number(c.isPrimitive(), (Object) (short) 0, n -> (short) Math.clamp(n.longValue(), Short.MIN_VALUE, Short.MAX_VALUE), true);
            }
            if (c == byte.class || c == Byte.class) {
                return number(c.isPrimitive(), (Object) (byte) 0, n -> (byte) Math.clamp(n.longValue(), Byte.MIN_VALUE, Byte.MAX_VALUE), true);
            }
            if (c == long.class || c == Long.class) {
                return number(c.isPrimitive(), (Object) 0L, Number::longValue, true);
            }
            if (c == double.class || c == Double.class) {
                return number(c.isPrimitive(), (Object) 0.0d, Number::doubleValue, false);
            }
            if (c == float.class || c == Float.class) {
                return number(c.isPrimitive(), (Object) 0.0f, Number::floatValue, false);
            }
            if (c == boolean.class || c == Boolean.class) {
                Object zero = c.isPrimitive() ? Boolean.FALSE : null;
                return simple(o -> o, w -> w instanceof Boolean b ? b : zero);
            }
            if (c == String.class) {
                return simple(o -> o, w -> w instanceof String s ? s : null);
            }
            if (c == UUID.class) {
                return simple(o -> o.toString(), w -> w instanceof String s ? UUID.fromString(s) : null);
            }
            if (c == Instant.class) {
                return simple(o -> ((Instant) o).toEpochMilli(),
                        w -> w instanceof Number n ? Instant.ofEpochMilli(n.longValue()) : null);
            }
            if (c.isEnum()) {
                return simple(o -> ((Enum<?>) o).name(), w -> w instanceof String s ? enumValue((Class) c, s) : null);
            }
            if (c.isRecord()) {
                RecordCodec<?> rc = RecordCodec.of((Class<? extends Record>) c);
                return (FieldCodec<Object>) (FieldCodec) rc;
            }
            throw new IllegalArgumentException("unsupported type " + c.getName());
        }
        if (t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw) {
            Type[] args = p.getActualTypeArguments();
            if (raw == List.class || raw == Set.class) {
                FieldCodec<Object> element = codecFor(args[0]);
                return collection(element, raw == Set.class);
            }
            if (raw == Map.class) {
                if (args[0] != String.class) {
                    throw new IllegalArgumentException("map keys must be String, was " + args[0].getTypeName());
                }
                return map(codecFor(args[1]));
            }
        }
        throw new IllegalArgumentException("unsupported type " + t.getTypeName());
    }

    /** Codec for the elements of SET and LIST fields. */
    static FieldCodec<Object> element(Class<?> elementType) {
        return codecFor(elementType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @Nullable Object enumValue(Class<? extends Enum> c, String name) {
        try {
            return Enum.valueOf(c, name);
        } catch (IllegalArgumentException e) {
            return null; // unknown constant, e.g. removed in a newer version: treat as missing
        }
    }

    private static FieldCodec<Object> number(boolean primitive, Object zero, Function<Number, Object> read,
                                             boolean integral) {
        return simple(o -> integral ? (Object) ((Number) o).longValue() : (Object) ((Number) o).doubleValue(),
                w -> w instanceof Number n ? read.apply(n) : primitive ? zero : null);
    }

    private static FieldCodec<Object> simple(Function<Object, Object> enc, Function<Object, Object> dec) {
        return new FieldCodec<>() {
            @Override
            public @Nullable Object encode(@Nullable Object value) {
                return value == null ? null : enc.apply(value);
            }

            @Override
            public @Nullable Object decode(@Nullable Object wire) {
                return dec.apply(wire);
            }
        };
    }

    private static FieldCodec<Object> collection(FieldCodec<Object> element, boolean set) {
        return new FieldCodec<>() {
            @Override
            public @Nullable Object encode(@Nullable Object value) {
                if (value == null) {
                    return null;
                }
                List<Object> out = new ArrayList<>();
                for (Object o : (Collection<?>) value) {
                    out.add(element.encode(o));
                }
                return Collections.unmodifiableList(out);
            }

            @Override
            public @Nullable Object decode(@Nullable Object wire) {
                if (!(wire instanceof List<?> list)) {
                    return null;
                }
                Collection<Object> out = set ? new LinkedHashSet<>() : new ArrayList<>();
                for (Object o : list) {
                    Object v = element.decode(o);
                    if (v != null) {
                        out.add(v);
                    }
                }
                return set ? Collections.unmodifiableSet((Set<Object>) out) : Collections.unmodifiableList((List<Object>) out);
            }
        };
    }

    private static FieldCodec<Object> map(FieldCodec<Object> value) {
        return new FieldCodec<>() {
            @Override
            public @Nullable Object encode(@Nullable Object v) {
                if (v == null) {
                    return null;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    out.put((String) e.getKey(), value.encode(e.getValue()));
                }
                return Collections.unmodifiableMap(out);
            }

            @Override
            public @Nullable Object decode(@Nullable Object wire) {
                if (!(wire instanceof Map<?, ?> m)) {
                    return null;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    out.put((String) e.getKey(), value.decode(e.getValue()));
                }
                return Collections.unmodifiableMap(out);
            }
        };
    }
}
