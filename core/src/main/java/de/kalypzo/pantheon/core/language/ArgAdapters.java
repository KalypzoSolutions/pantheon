package de.kalypzo.pantheon.core.language;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/** Converts custom placeholder values to Components. Looked up along the class hierarchy. Thread safe. */
public final class ArgAdapters {

    private final Map<Class<?>, BiFunction<Object, Locale, Component>> adapters = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> void register(Class<T> type, BiFunction<T, Locale, Component> adapter) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(adapter, "adapter");
        adapters.put(type, (BiFunction<Object, Locale, Component>) (BiFunction<?, Locale, Component>) adapter);
    }

    /** The component for the value, if an adapter exists for its class or one of its supertypes. */
    public Optional<Component> adapt(Object value, Locale locale) {
        Queue<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(value.getClass());
        while (!queue.isEmpty()) {
            Class<?> c = queue.remove();
            if (!seen.add(c)) {
                continue;
            }
            BiFunction<Object, Locale, Component> adapter = adapters.get(c);
            if (adapter != null) {
                return Optional.of(adapter.apply(value, locale));
            }
            if (c.getSuperclass() != null) {
                queue.add(c.getSuperclass());
            }
            queue.addAll(java.util.List.of(c.getInterfaces()));
        }
        return Optional.empty();
    }
}
