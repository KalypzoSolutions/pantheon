package de.kalypzo.pantheon.core.data;

import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.Subscription;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Field listeners over the documents of one kind (E15). Diffs arrive from several threads (own writes, acks, change
 * events); per namespace and document the dispatcher remembers the view it last reported, so {@code before} is always
 * the previous {@code after} and listeners see the values in order, even if two diffs race. A listener fires only if
 * the decoded field value changes (5 and 5.0, unset and default count as equal), always on the executor. Callers
 * must serialize {@link #changed} per key; the executor should never run tasks inline (it would run listener code
 * while the caller holds the key's lock), paper passes a main-thread executor that always schedules.
 *
 * @param <K> the document key, for example the player's UUID
 */
public final class ChangeDispatcher<K> {

    @FunctionalInterface
    public interface Listener<K, T> {
        void changed(K key, @Nullable T before, @Nullable T after);
    }

    private final Executor executor;
    private final Logger log;
    private final List<Entry<?>> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private final Map<K, Map<String, Map<String, Object>>> reported = new HashMap<>(); // guarded by lock

    public ChangeDispatcher(Executor executor, Logger log) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.log = Objects.requireNonNull(log, "log");
    }

    public <T> Subscription add(Field<T> field, Listener<K, T> listener) {
        Entry<T> e = new Entry<>(FieldInfo.of(field), Objects.requireNonNull(listener, "listener"));
        listeners.add(e);
        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true)) listeners.remove(e);
        };
    }

    /**
     * The view of {@code key} changed in the namespaces of {@code diffs}; {@code current} returns the view as it is
     * now. Call it for every diff of a document that is reported (held and loaded).
     */
    public void changed(K key, List<RowDiff> diffs, Function<String, Map<String, Object>> current) {
        if (diffs.isEmpty()) return;
        List<Runnable> fire = new ArrayList<>();
        synchronized (lock) {
            Map<String, Map<String, Object>> last = reported.computeIfAbsent(key, k -> new HashMap<>());
            for (RowDiff diff : diffs) {
                String ns = diff.namespace();
                Map<String, Object> before = last.getOrDefault(ns, diff.before());
                Map<String, Object> after = current.apply(ns);
                if (before.equals(after)) continue;
                last.put(ns, after);
                for (Entry<?> e : listeners) {
                    if (e.info.field().namespace().name().equals(ns)) e.collect(key, before, after, fire);
                }
            }
        }
        // handed to the executor outside the dispatcher's lock; callers serialize per key (the player's monitor),
        // so the submissions of one key keep their order
        for (Runnable r : fire) executor.execute(r);
    }

    /** Remembers {@code views} (namespace to data) as reported for {@code key}, e.g. right after a load. */
    public void seed(K key, Map<String, Map<String, Object>> views) {
        synchronized (lock) {
            reported.put(key, new HashMap<>(views));
        }
    }

    /** Forgets what was reported for {@code key}: it was released or (re)loaded. */
    public void reset(K key) {
        synchronized (lock) {
            reported.remove(key);
        }
    }

    private final class Entry<T> {
        private final FieldInfo<T> info;
        private final Listener<K, T> listener;

        Entry(FieldInfo<T> info, Listener<K, T> listener) {
            this.info = info;
            this.listener = listener;
        }

        void collect(K key, Map<String, Object> before, Map<String, Object> after, List<Runnable> out) {
            T b = info.decode(before);
            T a = info.decode(after);
            if (Objects.equals(b, a)) return;
            out.add(() -> {
                if (!listeners.contains(this)) return; // unsubscribed meanwhile
                try {
                    listener.changed(key, b, a);
                } catch (RuntimeException ex) {
                    log.log(Level.SEVERE, "onChange listener for " + info.field() + " of " + key + " failed: " + ex,
                            ex);
                }
            });
        }
    }
}
