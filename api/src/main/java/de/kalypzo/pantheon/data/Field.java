package de.kalypzo.pantheon.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A typed field in a {@link DataNamespace}. Equality covers namespace, name, type and default; aliases do not count.
 */
public final class Field<T> {

    private final DataNamespace namespace;
    private final String name;
    private final FieldType type;
    private final Class<T> valueClass;
    private final @Nullable Class<?> elementClass;
    private final @Nullable T defaultValue;
    private final List<String> aliases;

    Field(DataNamespace namespace, String name, FieldType type, Class<T> valueClass, @Nullable Class<?> elementClass,
          @Nullable T defaultValue, List<String> aliases) {
        this.namespace = namespace;
        this.name = name;
        this.type = type;
        this.valueClass = valueClass;
        this.elementClass = elementClass;
        this.defaultValue = defaultValue;
        this.aliases = List.copyOf(aliases);
    }

    public DataNamespace namespace() {
        return namespace;
    }

    public String name() {
        return name;
    }

    public FieldType type() {
        return type;
    }

    public Class<T> valueClass() {
        return valueClass;
    }

    /** The element class of SET and LIST fields, the enum class of ENUM fields, otherwise {@code null}. */
    public @Nullable Class<?> elementClass() {
        return elementClass;
    }

    public @Nullable T defaultValue() {
        return defaultValue;
    }

    /** Reads {@code oldName} as long as the value under the new name is missing. */
    public Field<T> readFrom(String oldName) {
        DataNamespace.checkName(oldName, "field alias");
        List<String> list = new ArrayList<>(aliases);
        if (!list.contains(oldName)) {
            list.add(oldName);
        }
        return new Field<>(namespace, name, type, valueClass, elementClass, defaultValue, list);
    }

    public List<String> aliases() {
        return aliases;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Field<?> f
                && namespace == f.namespace
                && name.equals(f.name)
                && type == f.type
                && valueClass.getName().equals(f.valueClass.getName())
                && Objects.equals(className(elementClass), className(f.elementClass))
                && Objects.equals(defaultKey(), f.defaultKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace.name(), name, type, defaultKey());
    }

    // Classes are compared by name so a plugin reload (new ClassLoader) counts as identical.
    private static @Nullable String className(@Nullable Class<?> c) {
        return c == null ? null : c.getName();
    }

    private @Nullable Object defaultKey() {
        return defaultValue instanceof Enum<?> e ? e.name() : defaultValue;
    }

    @Override
    public String toString() {
        return namespace.name() + "." + name;
    }
}
