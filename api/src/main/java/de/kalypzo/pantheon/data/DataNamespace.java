package de.kalypzo.pantheon.data;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** An area for data, e.g. {@code economy}. Instances are interned by name. */
public final class DataNamespace {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    private static final Map<String, DataNamespace> INTERNED = new ConcurrentHashMap<>();

    private final String name;
    private final Map<String, Field<?>> fields = new HashMap<>();

    private DataNamespace(String name) {
        this.name = name;
    }

    public static DataNamespace of(String name) {
        checkName(name, "namespace");
        return INTERNED.computeIfAbsent(name, DataNamespace::new);
    }

    static void checkName(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid " + what + " name '" + name + "', must match [a-z0-9_]+");
        }
    }

    public String name() {
        return name;
    }

    public Field<Integer> ofInt(String name, int defaultValue) {
        return declare(name, FieldType.INT, Integer.class, null, defaultValue);
    }

    public Field<Long> ofLong(String name, long defaultValue) {
        return declare(name, FieldType.LONG, Long.class, null, defaultValue);
    }

    public Field<Double> ofDouble(String name, double defaultValue) {
        return declare(name, FieldType.DOUBLE, Double.class, null, defaultValue);
    }

    public Field<Boolean> ofBoolean(String name, boolean defaultValue) {
        return declare(name, FieldType.BOOLEAN, Boolean.class, null, defaultValue);
    }

    public Field<String> ofString(String name, String defaultValue) {
        return declare(name, FieldType.STRING, String.class, null, defaultValue);
    }

    public Field<UUID> ofUuid(String name) {
        return declare(name, FieldType.UUID, UUID.class, null, null);
    }

    public <E extends Enum<E>> Field<E> ofEnum(String name, Class<E> type, E defaultValue) {
        return declare(name, FieldType.ENUM, type, type, defaultValue);
    }

    public Field<Instant> ofInstant(String name) {
        return declare(name, FieldType.INSTANT, Instant.class, null, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <E> Field<Set<E>> ofSet(String name, Class<E> elementType) {
        checkElement(elementType);
        return declare(name, FieldType.SET, (Class) Set.class, elementType, Set.<E>of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <E> Field<List<E>> ofList(String name, Class<E> elementType) {
        checkElement(elementType);
        return declare(name, FieldType.LIST, (Class) List.class, elementType, List.<E>of());
    }

    public <R extends Record> Field<R> of(String name, Class<R> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type.getName() + " is not a record");
        }
        return declare(name, FieldType.OBJECT, type, null, null);
    }

    private static void checkElement(Class<?> type) {
        boolean ok = type == String.class || type == UUID.class || type == Integer.class || type == Long.class
                || type.isEnum();
        if (!ok) {
            throw new IllegalArgumentException("Unsupported element type " + type.getName()
                    + ", allowed: String, UUID, Integer, Long, enums");
        }
    }

    private <T> Field<T> declare(String fieldName, FieldType type, Class<T> valueClass, @Nullable Class<?> element,
                                 @Nullable T defaultValue) {
        checkName(fieldName, "field");
        Field<T> field = new Field<>(this, fieldName, type, valueClass, element, defaultValue, List.of());
        synchronized (fields) {
            Field<?> existing = fields.get(fieldName);
            if (existing == null) {
                fields.put(fieldName, field);
                return field;
            }
            if (!existing.equals(field)) {
                throw new IllegalArgumentException("Field " + this.name + "." + fieldName
                        + " is already declared with a different type or default");
            }
            // Replace the entry so a reloaded plugin's ClassLoader is not pinned by the old field.
            fields.put(fieldName, field);
            return field;
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
