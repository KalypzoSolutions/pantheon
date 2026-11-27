package de.kalypzo.pantheon.core.runtime;

import de.kalypzo.pantheon.core.player.PlayerChangeListener;
import de.kalypzo.pantheon.core.player.PlayerDataEngine;
import de.kalypzo.pantheon.core.store.StoreRegistry;
import de.kalypzo.pantheon.data.CachedStore;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.FieldListener;
import de.kalypzo.pantheon.data.Store;
import de.kalypzo.pantheon.data.Subscription;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Everything one plugin registered through its Pantheon instance (megalodon §1, Review-Fokus 5): the
 * {@link Subscription}s of its {@code onChange} listeners and its shares of cached stores. {@link #close()} (the
 * plugin's {@code PluginDisableEvent}) unsubscribes and releases all of them, so nothing keeps the plugin's
 * ClassLoader alive. Listeners are also guarded: a listener task already queued on the main thread does not run after
 * the close. Writes are not part of the scope; they run on the shared {@code DocWriter} and finish. A closed scope
 * registers nothing new. Thread-safe.
 */
public class PluginScope implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PluginScope.class.getName());

    private final Object lock = new Object();
    private final Set<Subscription> subscriptions = new LinkedHashSet<>(); // guarded by lock
    private volatile boolean closed;

    public final boolean isClosed() {
        return closed;
    }

    /**
     * Ties {@code subscription} to this scope. The returned subscription unsubscribes and forgets it; on a closed scope
     * {@code subscription} is unsubscribed at once.
     */
    public final Subscription track(Subscription subscription) {
        Objects.requireNonNull(subscription, "subscription");
        Subscription[] tracked = new Subscription[1];
        AtomicBoolean done = new AtomicBoolean();
        tracked[0] = () -> {
            if (!done.compareAndSet(false, true)) return; // a cached share must be released exactly once
            synchronized (lock) {
                subscriptions.remove(tracked[0]);
            }
            subscription.unsubscribe();
        };
        synchronized (lock) {
            if (!closed) {
                subscriptions.add(tracked[0]);
                return tracked[0];
            }
        }
        tracked[0].unsubscribe();
        return tracked[0];
    }

    /** {@code listener}, silent once this scope is closed. */
    public final <T> FieldListener<T> guard(FieldListener<T> listener) {
        Objects.requireNonNull(listener, "listener");
        return (doc, before, after) -> {
            if (!closed) listener.changed(doc, before, after);
        };
    }

    /** {@code listener}, silent once this scope is closed. */
    public final <T> PlayerChangeListener<T> guard(PlayerChangeListener<T> listener) {
        Objects.requireNonNull(listener, "listener");
        return (player, before, after) -> {
            if (!closed) listener.changed(player, before, after);
        };
    }

    /** {@code store} as seen by this plugin: listeners and cached shares belong to this scope. */
    public final Store store(Store store, StoreRegistry registry) {
        return new ScopedStore(this, store, registry);
    }

    /** A player field listener of this plugin on {@code engine}. */
    public final <T> Subscription onPlayerChange(PlayerDataEngine engine, Field<T> field,
                                                 PlayerChangeListener<T> listener) {
        return track(engine.onChange(field, guard(listener)));
    }

    /** Ties a share of a cached store to this scope; {@link #close()} gives it back to the registry. */
    final CachedStore trackCached(CachedStore cached, StoreRegistry registry) {
        track(() -> registry.release(cached));
        return cached;
    }

    /** Unsubscribes every listener and releases every cached share of this plugin. Idempotent. */
    @Override
    public void close() {
        List<Subscription> all;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            all = new ArrayList<>(subscriptions);
            subscriptions.clear();
        }
        for (Subscription s : all) {
            try {
                s.unsubscribe(); // the tracked wrapper: idempotent
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not remove a subscription of a disabled plugin", e);
            }
        }
    }
}
