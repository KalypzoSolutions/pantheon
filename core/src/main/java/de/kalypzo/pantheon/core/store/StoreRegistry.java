package de.kalypzo.pantheon.core.store;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.ConnectionState;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.data.FieldInfo;
import de.kalypzo.pantheon.core.data.RowDiff;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.data.CachedStore;
import de.kalypzo.pantheon.data.FieldListener;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.data.Store;
import de.kalypzo.pantheon.data.Subscription;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * All stores of this node (data.md §4-§6). Hands out one {@link Store} per namespace and name and counts the
 * reasons to watch a store over all plugins: the first {@code cached()} or {@code onChange} calls
 * {@code watchStore}, the last teardown {@code unwatchStore}. {@code DocChanged} of stores nobody watches is ignored.
 *
 * <p>After a reconnect P-22 calls {@link #reloadCached()} (E23). Listeners run on {@code listenerExecutor}.
 */
public final class StoreRegistry implements AutoCloseable {

    /** {@code cached()} and reloads wait at most this long (E23). */
    public static final Duration LOAD_TIMEOUT = Duration.ofSeconds(10);
    /** Documents per page when loading a cached store (E6). */
    public static final int PAGE = 1000;

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    /**
     * Documents remembered per watched, uncached store (R47): {@code onChange}'s {@code before} is best-effort, the
     * last value seen on this node, otherwise the field's default.
     */
    static volatile int knownLimit = 10_000; // not final: tests shrink it
    private static final int VERSIONS_LIMIT = 100_000;

    private final Backend backend;
    private final DocWriter writer;
    private final Executor listenerExecutor;
    private final Logger log;

    private final Object lock = new Object();
    /** Per store: serializes {@code cached()} and release, held while loading (<= 10 s); other stores are not blocked. */
    private final Map<StoreRef, Object> cacheLocks = new ConcurrentHashMap<>();
    private final Object retryLock = new Object();
    private @Nullable ScheduledExecutorService retries; // guarded by retryLock
    private final Map<StoreRef, StoreImpl> stores = new HashMap<>();   // guarded by lock
    private final Map<StoreRef, Watch> watches = new HashMap<>();      // guarded by lock
    private final Subscription docChanged;
    private final Subscription diffs;
    private boolean closed; // guarded by lock

    public StoreRegistry(Backend backend, DocWriter writer, Executor listenerExecutor, Logger log) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.listenerExecutor = Objects.requireNonNull(listenerExecutor, "listenerExecutor");
        this.log = Objects.requireNonNull(log, "log");
        this.docChanged = backend.onDocChanged(this::onDocChanged);
        this.diffs = writer.onDiff(this::onDiff);
    }

    public Store store(DataNamespace namespace, String name) {
        Objects.requireNonNull(namespace, "namespace");
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid store name '" + name + "', must match [a-z0-9_]+");
        }
        StoreRef ref = new StoreRef(namespace.name(), name);
        synchronized (lock) {
            return stores.computeIfAbsent(ref, r -> new StoreImpl(this, r));
        }
    }

    /** The stores this node watches, for {@code NodeState}. */
    public Collection<StoreRef> watchedStores() {
        synchronized (lock) {
            return List.copyOf(watches.keySet());
        }
    }

    /**
     * Loads every cached store completely again (E23), one after the other, each within 10 s. Blocks, so call it off
     * the backend's stream thread. A store that cannot be loaded keeps its content; the error is logged.
     */
    public void reloadCached() {
        List<CachedStoreImpl> caches = new ArrayList<>();
        synchronized (lock) {
            for (Watch w : watches.values()) {
                if (w.cache != null) caches.add(w.cache);
            }
        }
        for (CachedStoreImpl cache : caches) {
            try {
                cache.reload();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.log(Level.SEVERE, "Interrupted while reloading cached store " + cache.ref.qualified());
                return;
            } catch (Exception e) {
                log.log(Level.SEVERE, "Reloading cached store " + cache.ref.qualified() + " failed: " + e, e);
            }
        }
    }

    @Override
    public void close() {
        List<CachedStoreImpl> caches = new ArrayList<>();
        List<StoreRef> unwatch = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            closed = true;
            for (Map.Entry<StoreRef, Watch> e : watches.entrySet()) {
                if (e.getValue().cache != null) caches.add(e.getValue().cache);
                unwatch.add(e.getKey());
            }
            watches.clear();
        }
        docChanged.unsubscribe();
        diffs.unsubscribe();
        synchronized (retryLock) {
            if (retries != null) retries.shutdownNow();
        }
        for (CachedStoreImpl c : caches) c.dispose();
        for (StoreRef ref : unwatch) backend.unwatchStore(ref);
    }

    // ---------- used by the store classes ----------

    Backend backend() {
        return backend;
    }

    DocWriter writer() {
        return writer;
    }

    Logger log() {
        return log;
    }

    CachedStoreImpl cached(StoreImpl store) {
        StoreRef ref = store.ref;
        synchronized (cacheLocks.computeIfAbsent(ref, r -> new Object())) {
            Watch w;
            boolean created = false;
            synchronized (lock) {
                w = acquireWatch(ref);
                if (w.cache == null) {
                    CachedStoreImpl c = new CachedStoreImpl(this, ref);
                    w.cache = c;
                    synchronized (w.known) {
                        w.known.clear();
                    }
                    created = true;
                } else {
                    releaseWatch(ref); // the cache already holds one
                }
                w.cacheRefs++;
            }
            CachedStoreImpl cache = w.cache;
            if (created) {
                try {
                    cache.load(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.log(Level.SEVERE, "Interrupted while loading cached store " + ref.qualified()
                            + ", starting empty");
                } catch (Exception e) {
                    log.log(Level.SEVERE, "Loading cached store " + ref.qualified() + " failed, starting empty; retrying"
                            + " with backoff and reloading after the next reconnect: " + e, e);
                    scheduleRetry(cache, 1);
                }
            }
            return cache;
        }
    }

    /** Gives up one share of a cached store obtained from {@code cached()}; the last one stops watching (P-23). */
    public void release(CachedStore cached) {
        if (cached instanceof CachedStoreImpl impl) releaseCache(impl);
    }

    /** Retries a failed initial load while connected: 1 s, 2 s, 4 s ... at most every 30 s, until it works. */
    private void scheduleRetry(CachedStoreImpl cache, int delaySeconds) {
        synchronized (retryLock) {
            if (closed()) return;
            if (retries == null) {
                retries = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "pantheon-store-retry");
                    t.setDaemon(true);
                    return t;
                });
            }
            retries.schedule(() -> retry(cache, delaySeconds), delaySeconds, TimeUnit.SECONDS);
        }
    }

    private void retry(CachedStoreImpl cache, int delaySeconds) {
        if (closed() || cache.isDisposed() || cache.isLoaded()) return;
        int next = Math.min(delaySeconds * 2, 30);
        if (backend.state() != ConnectionState.CONNECTED) {
            scheduleRetry(cache, next);
            return;
        }
        try {
            cache.reload();
            log.info("Cached store " + cache.ref.qualified() + " loaded after a retry");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.log(Level.WARNING, "Retry loading cached store " + cache.ref.qualified() + " failed: " + e);
            scheduleRetry(cache, next);
        }
    }

    private boolean closed() {
        synchronized (lock) {
            return closed;
        }
    }

    void releaseCache(CachedStoreImpl cache) {
        synchronized (cacheLocks.computeIfAbsent(cache.ref, r -> new Object())) {
            synchronized (lock) {
                Watch w = watches.get(cache.ref);
                if (w == null || w.cache != cache) return;
                if (--w.cacheRefs > 0) return;
                w.cache = null;
                cache.dispose();
                releaseWatch(cache.ref);
            }
        }
    }

    <T> Subscription addListener(StoreRef ref, FieldInfo<T> info, FieldListener<T> listener) {
        Listener<T> l = new Listener<>(info, listener);
        Watch w;
        synchronized (lock) {
            w = acquireWatch(ref);
            w.listeners.add(l);
        }
        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (!done.compareAndSet(false, true)) return;
            synchronized (lock) {
                w.listeners.remove(l);
                releaseWatch(ref);
            }
        };
    }

    /**
     * Own writes and acks of a cached document reach the listeners like remote changes. A document the cache does not
     * hold yet is adopted if the write makes it visible (agora.md §8: own writes act locally, also during an outage).
     */
    void localDiffs(DocRef doc, List<RowDiff> diffs) {
        if (diffs.isEmpty() || !(doc instanceof StoreDoc d)) return;
        CachedStoreImpl cache = cacheOf(d.store());
        if (cache != null && cache.adoptLocal(d.id(), diffs)) {
            cache.fire(d.id(), diffs);
            cache.dropIfGone(d.id());
        }
    }

    /** An unconditional own write of a store document; returns at once (DocWriter#write), visible locally. */
    void write(StoreDoc doc, List<de.kalypzo.pantheon.core.data.wire.FieldOp> ops) {
        CachedStoreImpl cache = cacheOf(doc.store());
        if (cache == null || cache.contains(doc.id())) {
            localDiffs(doc, writer.write(doc, ops));
            return;
        }
        // Holds the writer's handle across the write: an immediate ack must not drop it before the cache adopts it.
        writer.acquire(doc);
        try {
            localDiffs(doc, writer.write(doc, ops));
        } finally {
            writer.release(doc);
        }
    }

    /** An own {@code create} succeeded: a cached store shows the row at once. */
    void localCreated(StoreDoc doc, de.kalypzo.pantheon.core.data.wire.@Nullable Row row) {
        CachedStoreImpl cache = cacheOf(doc.store());
        if (cache != null && row != null) cache.createdLocally(doc.id(), row);
    }

    /** An own {@code delete} (request {@code key}) removed the document: a cached store drops it at once. */
    void localDeleted(StoreDoc doc, String key) {
        CachedStoreImpl cache = cacheOf(doc.store());
        if (cache != null) cache.deletedLocally(doc.id(), key);
    }

    private @Nullable CachedStoreImpl cacheOf(StoreRef ref) {
        synchronized (lock) {
            Watch w = watches.get(ref);
            return w == null ? null : w.cache;
        }
    }

    void fire(StoreRef ref, String id, Map<String, Object> before, Map<String, Object> after) {
        Watch w;
        StoreImpl store;
        synchronized (lock) {
            w = watches.get(ref);
            store = stores.get(ref);
        }
        if (w == null || store == null) return;
        for (Listener<?> l : w.listeners) l.fire(store, id, before, after);
    }

    // ---------- internals ----------

    private void onDiff(DocRef doc, List<RowDiff> diffs) {
        localDiffs(doc, diffs);
    }

    private void onDocChanged(DocChanged c) {
        if (!(c.ref() instanceof StoreDoc d)) return;
        Watch w;
        synchronized (lock) {
            w = watches.get(d.store());
        }
        if (w == null) return; // A-10: a store nobody watches
        CachedStoreImpl cache = w.cache;
        if (cache != null) {
            cache.accept(c);
            return;
        }
        if (w.listeners.isEmpty()) return;
        Map<String, Object> before;
        Map<String, Object> after = c.deleted() ? Map.of() : c.data();
        synchronized (w.known) {
            Long seen = w.versions.get(d.id());
            if (seen != null && seen >= c.version()) return; // old or duplicate, also for evicted documents
            w.versions.put(d.id(), c.version());
            before = w.known.get(d.id());
            if (before == null) before = Map.of();
            if (c.deleted()) w.known.remove(d.id());
            else w.known.put(d.id(), after);
        }
        fire(d.store(), d.id(), before, after);
    }

    /** Callers hold {@code lock}. Backend calls under it are fine: watch and unwatch are fire-and-forget. */
    private Watch acquireWatch(StoreRef ref) {
        Watch w = watches.computeIfAbsent(ref, r -> new Watch());
        if (++w.refs == 1 && !closed) backend.watchStore(ref);
        return w;
    }

    private void releaseWatch(StoreRef ref) {
        Watch w = watches.get(ref);
        if (w == null) return;
        if (--w.refs <= 0) {
            watches.remove(ref);
            if (!closed) backend.unwatchStore(ref);
        }
    }

    private static final class Watch {
        int refs;                       // guarded by lock
        int cacheRefs;                  // guarded by lock
        volatile @Nullable CachedStoreImpl cache;
        final List<Listener<?>> listeners = new CopyOnWriteArrayList<>();
        /** Last state seen per document (access-order LRU); guarded by itself together with {@code versions}. */
        final Map<String, Map<String, Object>> known = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                return size() > knownLimit;
            }
        };
        /** Last version seen per document; larger than {@code known}, so stale events stay ignored after eviction. */
        final Map<String, Long> versions = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > VERSIONS_LIMIT;
            }
        };
    }

    private final class Listener<T> {
        private final FieldInfo<T> info;
        private final FieldListener<T> listener;

        Listener(FieldInfo<T> info, FieldListener<T> listener) {
            this.info = info;
            this.listener = listener;
        }

        void fire(StoreImpl store, String id, Map<String, Object> before, Map<String, Object> after) {
            // compare decoded values: 5 and 5.0 are the same, and an unset field equals its default
            T b = info.decodeRaw(info.raw(before));
            T a = info.decodeRaw(info.raw(after));
            if (Objects.equals(b, a)) return;
            listenerExecutor.execute(() -> {
                try {
                    listener.changed(store.snapshot(id, after), b, a);
                } catch (RuntimeException e) {
                    log.log(Level.SEVERE, "onChange listener for " + info.field() + " of " + store.ref.qualified()
                            + "/" + id + " failed: " + e, e);
                }
            });
        }
    }
}
