package de.kalypzo.pantheon.core.store;

import de.kalypzo.pantheon.core.data.DocHandle;
import de.kalypzo.pantheon.core.data.LiveDoc;
import de.kalypzo.pantheon.core.data.RowDiff;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.DocSnapshot;
import de.kalypzo.pantheon.core.data.wire.QuerySpec;
import de.kalypzo.pantheon.core.data.wire.QueryTarget;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.data.CachedStore;
import de.kalypzo.pantheon.data.Doc;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * A store kept completely in memory (E23): loaded in pages of 1000 documents within 10 s, then live over
 * {@code DocChanged}. Documents are the writer's {@link DocHandle}s, so own writes are visible at once. After a
 * reconnect {@link #reload()} loads everything again (the registry's {@code reloadCached}, called by P-22).
 *
 * <p>Own changes act locally at once (agora.md §8), also for documents the cache does not hold yet and during an
 * outage: a write that makes a new document visible adopts the writer's handle ({@link #adoptLocal}), a successful
 * {@code create} adopts the created row ({@link #createdLocally}) and a successful {@code delete} removes the document
 * ({@link #deletedLocally}). Their echo then changes nothing: rows are adopted by version, and events of a locally
 * deleted document are ignored until the delete's own echo (same idempotency key) has arrived, because the stream
 * delivers every older event before it. A document with pending own ops is never dropped by a load or a remote
 * delete: the ops recreate it.
 */
final class CachedStoreImpl extends StoreImpl implements CachedStore {

    /** Recently deleted ids and delete keys remembered (for late create results and echoes). */
    private static final int RECENT_LIMIT = 1024;

    private final ConcurrentHashMap<String, DocHandle> docs = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private boolean loading;                    // guarded by lock
    private @Nullable Set<String> touched;      // ids changed by events while loading; guarded by lock
    private @Nullable Set<String> deleted;      // ids deleted by events while loading; guarded by lock
    private boolean disposed;                   // guarded by lock
    private volatile boolean loaded;            // a load has completed
    /** Locally deleted documents whose delete echo (value: its idempotency key) has not arrived; guarded by lock. */
    private final Map<String, String> localDeletes = new HashMap<>();
    /** Idempotency keys of recently seen delete events; guarded by lock. */
    private final Set<String> seenDeleteKeys = bounded();
    /** Version of the last delete event per recently deleted id; guarded by lock. */
    private final Map<String, Long> gone = boundedMap();

    CachedStoreImpl(StoreRegistry registry, StoreRef ref) {
        super(registry, ref);
    }

    @Override
    public Optional<Doc> get(String id) {
        DocHandle h = docs.get(id);
        return h == null ? Optional.empty() : Optional.of(live(id, h));
    }

    @Override
    public Collection<Doc> all() {
        List<Doc> out = new ArrayList<>(docs.size());
        for (var e : docs.entrySet()) out.add(live(e.getKey(), e.getValue()));
        return out;
    }

    private Doc live(String id, DocHandle h) {
        return new LiveDoc(id, h, target(id));
    }

    boolean isLoaded() {
        return loaded;
    }

    boolean isDisposed() {
        synchronized (lock) {
            return disposed;
        }
    }

    boolean contains(String id) {
        return docs.containsKey(id);
    }

    @Override
    public de.kalypzo.pantheon.data.CachedStore cached() {
        return this;
    }

    /** Gives up this handle's share of the cache; the last release stops watching the store. */
    public void release() {
        registry.releaseCache(this);
    }

    /**
     * Loads the whole store again, e.g. after a reconnect; documents that no longer exist disappear. Until a load has
     * completed (the initial load failed, this is its retry or the reload after the reconnect), it counts as the
     * initial load and fires no listeners, like a successful initial load.
     */
    public void reload() throws Exception {
        load(!loaded);
    }

    /**
     * Blocks at most {@link StoreRegistry#LOAD_TIMEOUT}. On failure the content stays as it was. {@code initial}
     * loads fire no listeners.
     */
    void load(boolean initial) throws Exception {
        synchronized (lock) {
            // deletes that completed before this load are in what it reads; their echo may be lost (reconnect)
            localDeletes.clear();
            loading = true;
            touched = new HashSet<>();
            deleted = new HashSet<>();
        }
        try {
            long end = System.nanoTime() + StoreRegistry.LOAD_TIMEOUT.toNanos();
            QueryTarget target = new QueryTarget.StoreTarget(ref);
            Set<String> loaded = new HashSet<>();
            int offset = 0;
            while (true) {
                long left = end - System.nanoTime();
                if (left <= 0) throw new TimeoutException("loading " + ref.qualified() + " took longer than 10 s");
                var future = registry.backend().query(target,
                        new QuerySpec(List.of(), List.of(), StoreRegistry.PAGE, offset));
                List<DocSnapshot> page;
                try {
                    page = future.get(left, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    throw e;
                }
                for (DocSnapshot s : page) {
                    if (s.rows().isEmpty() || !(s.doc() instanceof StoreDoc d)) continue;
                    loaded.add(d.id());
                    adopt(d.id(), s.rows().getFirst(), !initial);
                }
                if (page.size() < StoreRegistry.PAGE) break;
                offset += page.size();
            }
            List<String> stale = new ArrayList<>();
            synchronized (lock) {
                for (var e : docs.entrySet()) {
                    String id = e.getKey();
                    if (!loaded.contains(id) && !touched.contains(id) && e.getValue().pending().isEmpty()) {
                        stale.add(id); // pending own ops recreate the document, it stays
                    }
                }
            }
            for (String id : stale) remove(id, !initial);
            this.loaded = true;
        } finally {
            synchronized (lock) {
                loading = false;
                touched = null;
                deleted = null;
            }
        }
    }

    private void adopt(String id, Row row, boolean fire) {
        List<RowDiff> diffs;
        synchronized (lock) {
            if (disposed || (deleted != null && deleted.contains(id)) || localDeletes.containsKey(id)) return;
            DocHandle h = docs.get(id);
            if (h == null) {
                h = registry.writer().acquire(new StoreDoc(ref, id));
                docs.put(id, h);
            }
            diffs = h.replaceAll(List.of(row));
        }
        if (fire) fire(id, diffs);
    }

    private void remove(String id, boolean fire) {
        List<RowDiff> diffs = List.of();
        synchronized (lock) {
            DocHandle h = docs.remove(id);
            if (h == null) return;
            if (fire) diffs = h.replaceAll(List.of());
            registry.writer().release(new StoreDoc(ref, id));
        }
        fire(id, diffs);
    }

    /** A change event of this store (stream order). */
    void accept(DocChanged c) {
        if (!(c.ref() instanceof StoreDoc d)) return;
        String id = d.id();
        List<RowDiff> diffs;
        synchronized (lock) {
            if (disposed) return;
            if (c.deleted()) {
                gone.put(id, c.version());
                if (c.idempotencyKey() != null) seenDeleteKeys.add(c.idempotencyKey());
            }
            String localDelete = localDeletes.get(id);
            if (localDelete != null) {
                // deleted here already; every event before the delete's own echo is older than the delete
                if (c.deleted() && localDelete.equals(c.idempotencyKey())) localDeletes.remove(id);
                return;
            }
            if (loading) {
                if (c.deleted()) {
                    deleted.add(id);
                    touched.remove(id);
                } else {
                    deleted.remove(id);
                    touched.add(id);
                }
            }
            DocHandle h = docs.get(id);
            if (c.deleted()) {
                if (h == null) return;
                diffs = h.accept(c);
                if (h.pending().isEmpty()) { // pending own ops (called after the delete) recreate it
                    docs.remove(id);
                    registry.writer().release(d);
                }
            } else {
                if (h == null) {
                    h = registry.writer().acquire(d);
                    docs.put(id, h);
                }
                diffs = h.accept(c);
            }
        }
        fire(id, diffs);
    }

    /**
     * An own write changed document {@code id}, which this cache does not hold: adopts the writer's handle if the
     * write makes the document visible (a non-empty view in this store's namespace). Returns whether the cache now
     * holds the document.
     */
    boolean adoptLocal(String id, List<RowDiff> diffs) {
        boolean visible = false;
        for (RowDiff diff : diffs) {
            if (diff.namespace().equals(ref.namespace()) && !diff.after().isEmpty()) visible = true;
        }
        synchronized (lock) {
            if (disposed) return false;
            if (docs.containsKey(id)) return true;
            if (!visible) return false;
            localDeletes.remove(id); // written again after an own delete
            markLocal(id);
            docs.put(id, registry.writer().acquire(new StoreDoc(ref, id)));
            return true;
        }
    }

    /** After an own write's diffs: a document without any known row or pending op does not exist (any more). */
    void dropIfGone(String id) {
        synchronized (lock) {
            DocHandle h = docs.get(id);
            if (h == null || !h.namespaces().isEmpty()) return;
            docs.remove(id);
            registry.writer().release(new StoreDoc(ref, id));
        }
    }

    /** An own {@code create} succeeded: the created row is visible at once (its echo then changes nothing). */
    void createdLocally(String id, Row row) {
        List<RowDiff> diffs;
        synchronized (lock) {
            if (disposed) return;
            DocHandle h = docs.get(id);
            if (h == null) {
                Long deletedAt = gone.get(id);
                if (deletedAt != null && deletedAt >= row.version()) return; // deleted again meanwhile
                h = registry.writer().acquire(new StoreDoc(ref, id));
                if (h.version(ref.namespace()) > row.version()) { // a newer event (a delete) was seen already
                    registry.writer().release(new StoreDoc(ref, id));
                    return;
                }
                localDeletes.remove(id);
                markLocal(id);
                docs.put(id, h);
            }
            diffs = h.merge(List.of(row));
        }
        fire(id, diffs);
    }

    /** An own {@code delete} (request {@code key}) removed the document: it disappears at once. */
    void deletedLocally(String id, String key) {
        List<RowDiff> diffs;
        synchronized (lock) {
            if (disposed || seenDeleteKeys.contains(key)) return; // the echo was already applied
            DocHandle h = docs.get(id);
            if (h == null) return;
            diffs = h.replaceAll(List.of());
            if (h.pending().isEmpty()) { // ops called after the delete recreate the document
                docs.remove(id);
                registry.writer().release(new StoreDoc(ref, id));
                localDeletes.put(id, key);
                if (loading) {
                    deleted.add(id);
                    touched.remove(id);
                }
            }
        }
        fire(id, diffs);
    }

    /** Caller holds {@code lock}: a running load must not drop a document adopted locally meanwhile. */
    private void markLocal(String id) {
        if (loading) {
            deleted.remove(id);
            touched.add(id);
        }
    }

    private static Set<String> bounded() {
        return java.util.Collections.newSetFromMap(boundedMap());
    }

    private static <V> Map<String, V> boundedMap() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > RECENT_LIMIT;
            }
        };
    }

    void fire(String id, List<RowDiff> diffs) {
        for (RowDiff diff : diffs) {
            if (diff.namespace().equals(ref.namespace())) registry.fire(ref, id, diff.before(), diff.after());
        }
    }

    /** Drops every document; the registry stops feeding this cache. */
    void dispose() {
        synchronized (lock) {
            disposed = true;
            for (String id : docs.keySet()) registry.writer().release(new StoreDoc(ref, id));
            docs.clear();
        }
    }
}
