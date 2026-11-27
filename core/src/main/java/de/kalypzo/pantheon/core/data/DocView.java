package de.kalypzo.pantheon.core.data;

import de.kalypzo.pantheon.data.Field;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads fields from the data of a document, one map per namespace (data.md §7): first the field's name, then its
 * {@code readFrom} aliases in order, otherwise the default. Values are decoded through the field's codec, so wire
 * numbers are normalized to the field's type. Writing always uses the new name.
 */
public final class DocView {

    private final Function<String, Map<String, Object>> namespaces;

    /** {@code namespaces} returns the current data of a namespace, an empty map if there is none. */
    public DocView(Function<String, Map<String, Object>> namespaces) {
        this.namespaces = Objects.requireNonNull(namespaces, "namespaces");
    }

    public <T> T get(Field<T> field) {
        return FieldInfo.of(field).decode(data(field));
    }

    public <T> Optional<T> find(Field<T> field) {
        FieldInfo<T> info = FieldInfo.of(field);
        Object raw = info.raw(data(field));
        return raw == null ? Optional.empty() : Optional.ofNullable(info.decodeRaw(raw));
    }

    public boolean has(Field<?> field) {
        return FieldInfo.of(field).raw(data(field)) != null;
    }

    private Map<String, Object> data(Field<?> field) {
        Objects.requireNonNull(field, "field");
        Map<String, Object> data = namespaces.apply(field.namespace().name());
        return data == null ? Map.of() : data;
    }
}
