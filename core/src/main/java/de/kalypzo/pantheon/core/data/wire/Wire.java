package de.kalypzo.pantheon.core.data.wire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Helpers for wire values: {@code null | Boolean | Long | Double | String | List | Map<String,Object>}, immutable. */
public final class Wire {

    private Wire() {}

    /** Deep immutable copy of a wire value. Nulls inside lists and maps are kept. */
    public static @Nullable Object immutable(@Nullable Object value) {
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object o : list) {
                copy.add(immutable(o));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put((String) e.getKey(), immutable(e.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> immutableMap(Map<String, Object> map) {
        return (Map<String, Object>) immutable(map);
    }
}
