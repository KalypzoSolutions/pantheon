package de.kalypzo.pantheon.core.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One instance per plugin (megalodon §1). Life cycle of a plugin's instance (R51):
 * <ol>
 *   <li>{@link #get} creates it on first use while the plugin is enabled.
 *   <li>{@link #retire} (its {@code PluginDisableEvent}, which Paper fires before the plugin's {@code onDisable}): it
 *       stays usable and {@link #get} keeps returning it, so {@code onDisable} can still save and read cached stores.
 *   <li>{@link #close} (the next tick, or Pantheon's own disable): torn down and forgotten. A disabled plugin then gets
 *       an {@link IllegalStateException}, never a new instance (it would never be closed and keep the ClassLoader);
 *       an enabled one gets a fresh instance.
 * </ol>
 * Thread-safe.
 *
 * @param <K> the plugin
 * @param <I> the instance, a {@link PluginScope}
 */
public final class PantheonRegistry<K, I extends PluginScope> {

    private final Object lock = new Object();
    private final Map<K, I> instances = new ConcurrentHashMap<>();
    private final Map<K, I> retired = new ConcurrentHashMap<>(); // writes guarded by lock

    /** Exactly one instance per key, for a plugin that is enabled. Same as {@code get(key, true, factory)}. */
    public I get(K key, Function<? super K, ? extends I> factory) {
        return get(key, true, factory);
    }

    /**
     * The instance of {@code key}: a retired one until it is closed, otherwise the active one. Only if
     * {@code enabled} and there is none, {@code factory} creates it (at most once per key until it is closed).
     *
     * @throws IllegalStateException if the plugin is not enabled and has no instance (any more)
     */
    public I get(K key, boolean enabled, Function<? super K, ? extends I> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        // instances before retired: retire puts into retired before removing from instances, so a lock-free reader
        // racing it sees the instance in at least one of the two maps
        I active = instances.get(key);
        if (active != null) return active;
        I old = retired.get(key);
        if (old != null) return old;
        synchronized (lock) {
            old = retired.get(key);
            if (old != null) return old;
            if (!enabled) {
                active = instances.get(key);
                if (active != null) return active;
                throw new IllegalStateException("Plugin " + key + " is disabled; Pantheon gives it no new instance");
            }
            return instances.computeIfAbsent(key, factory);
        }
    }

    /**
     * The plugin is being disabled: its instance stays usable and is handed out until {@link #close}. Returns whether
     * there was an instance to retire.
     */
    public boolean retire(K key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            I instance = instances.get(key);
            if (instance == null) return retired.containsKey(key);
            retired.put(key, instance); // first into retired, then out of instances: never in neither map
            instances.remove(key);
            return true;
        }
    }

    /** Closes and forgets the instance of {@code key} (retired or active), if there is one. */
    public void close(K key) {
        Objects.requireNonNull(key, "key");
        I old;
        I active;
        synchronized (lock) {
            old = retired.remove(key);
            active = instances.remove(key);
        }
        if (old != null) old.close();
        if (active != null) active.close();
    }

    /** Closes every instance, retired ones included (Pantheon itself is disabled). */
    public void closeAll() {
        Set<K> keys;
        synchronized (lock) {
            keys = new LinkedHashSet<>(retired.keySet());
            keys.addAll(instances.keySet());
        }
        for (K key : new ArrayList<>(keys)) close(key);
    }
}
