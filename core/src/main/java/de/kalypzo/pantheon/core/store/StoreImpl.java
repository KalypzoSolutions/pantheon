package de.kalypzo.pantheon.core.store;

import de.kalypzo.pantheon.core.data.FieldInfo;
import de.kalypzo.pantheon.core.data.QueryImpl;
import de.kalypzo.pantheon.core.data.RemoteDocImpl;
import de.kalypzo.pantheon.core.data.SnapshotDoc;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.CreateResult;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.IndexDeclaration;
import de.kalypzo.pantheon.core.data.wire.QueryTarget;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.data.wire.TransferResult;
import de.kalypzo.pantheon.data.CachedStore;
import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.FieldListener;
import de.kalypzo.pantheon.data.Query;
import de.kalypzo.pantheon.data.RemoteDoc;
import de.kalypzo.pantheon.data.Store;
import de.kalypzo.pantheon.data.Subscription;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

/** A store (data.md §4). One instance per store and registry; the cached view of it is a {@link CachedStoreImpl}. */
class StoreImpl implements Store {

    final StoreRegistry registry;
    final StoreRef ref;

    StoreImpl(StoreRegistry registry, StoreRef ref) {
        this.registry = registry;
        this.ref = ref;
    }

    StoreDoc doc0(String id) {
        if (id == null || id.isEmpty() || id.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid document id: " + id);
        }
        return new StoreDoc(ref, id);
    }

    /** Every field of a store document belongs to the store's namespace. */
    void check(Field<?> field) {
        Objects.requireNonNull(field, "field");
        if (!field.namespace().name().equals(ref.namespace())) {
            throw new IllegalArgumentException("Field " + field + " does not belong to the namespace of store "
                    + ref.qualified());
        }
    }

    <T> FieldInfo<T> info(Field<T> field) {
        check(field);
        return FieldInfo.of(field);
    }

    /** Where the ops of document {@code id} go: the writer, with the namespace check of this store. */
    RemoteDocImpl.Target target(String id) {
        StoreDoc doc = doc0(id);
        return new RemoteDocImpl.Target() {
            @Override
            public void write(List<FieldOp> ops) {
                registry.write(doc, ops);
            }

            @Override
            public CompletableFuture<ApplyResult> writeConditional(List<FieldOp> ops, List<FieldCondition> conditions) {
                return registry.writer().writeConditional(doc, ops, conditions);
            }

            @Override
            public void check(Field<?> field) {
                StoreImpl.this.check(field);
            }
        };
    }

    /** For the {@code init} of {@code create}: only gathers ops, conditional ones are not available. */
    private RemoteDocImpl.Target collecting() {
        return new RemoteDocImpl.Target() {
            @Override
            public void write(List<FieldOp> ops) {} // the collecting snapshot keeps the data

            @Override
            public CompletableFuture<ApplyResult> writeConditional(List<FieldOp> ops, List<FieldCondition> conditions) {
                throw new UnsupportedOperationException(
                        "conditional operations are not available while creating a document");
            }

            @Override
            public void check(Field<?> field) {
                StoreImpl.this.check(field);
            }
        };
    }

    SnapshotDoc snapshot(String id, Map<String, Object> data) {
        return SnapshotDoc.of(id, target(id), ref.namespace(), data);
    }

    @Override
    public CompletableFuture<Optional<Doc>> load(String id) {
        StoreDoc doc = doc0(id);
        // queued behind the document's own ops, so a load sees what this node wrote before (R46)
        return registry.writer().enqueueCall(doc, key -> registry.backend().load(doc)).thenApply(rows -> rows.isEmpty()
                ? Optional.<Doc>empty()
                : Optional.of(snapshot(id, rows.getFirst().data())));
    }

    @Override
    public CompletableFuture<Doc> loadOrCreate(String id) {
        return load(id).thenCompose(found -> {
            if (found.isPresent()) return CompletableFuture.completedFuture(found.get());
            return createRaw(id, doc -> {}).thenCompose(result -> {
                if (result.created()) {
                    Row row = Objects.requireNonNull(result.row(), "created without row");
                    return CompletableFuture.completedFuture((Doc) snapshot(id, row.data()));
                }
                // someone else created it between our load and our create
                return load(id).thenApply(again -> again.orElseThrow(() ->
                        new IllegalStateException("Document " + ref.qualified() + "/" + id
                                + " was created and removed again while loading")));
            });
        });
    }

    @Override
    public CompletableFuture<Boolean> create(String id, Consumer<Doc> init) {
        return createRaw(id, init).thenApply(CreateResult::created);
    }

    /** {@code init} runs here on the caller's thread, on a collecting doc; its errors are thrown, not returned. */
    private CompletableFuture<CreateResult> createRaw(String id, Consumer<Doc> init) {
        StoreDoc doc = doc0(id);
        SnapshotDoc collecting = SnapshotDoc.of(id, collecting(), ref.namespace(), Map.of());
        init.accept(collecting);
        Map<String, Object> data = collecting.data(ref.namespace());
        return registry.writer().enqueueCall(doc, key -> registry.backend().create(key, doc, data)).thenApply(r -> {
            if (r.created()) registry.localCreated(doc, r.row()); // visible before the caller continues
            return r;
        });
    }

    @Override
    public RemoteDoc doc(String id) {
        return new RemoteDocImpl(id, target(id));
    }

    @Override
    public CompletableFuture<Boolean> delete(String id) {
        StoreDoc doc = doc0(id);
        String[] key = new String[1];
        return registry.writer().enqueueCall(doc, k -> {
            key[0] = k;
            return registry.backend().delete(k, doc);
        }).thenApply(deleted -> {
            if (deleted) registry.localDeleted(doc, key[0]); // gone before the caller continues
            return deleted;
        });
    }

    @Override
    public void expire(String id, Instant at) {
        Objects.requireNonNull(at, "at");
        StoreDoc doc = doc0(id);
        // E24: fire and forget, failures are only logged, never written to the outbox
        try {
            registry.writer().enqueueCall(doc, key -> registry.backend().expire(key, doc, at)).whenComplete((v, e) -> {
                if (e != null) logExpire(id, e);
            });
        } catch (RuntimeException e) {
            logExpire(id, e);
        }
    }

    private void logExpire(String id, Throwable e) {
        registry.log().log(Level.WARNING, "expire of " + ref.qualified() + "/" + id + " failed: " + e, e);
    }

    @Override
    public Query query() {
        QueryTarget target = new QueryTarget.StoreTarget(ref);
        return new QueryImpl(registry.backend(), target, this::check, s ->
                !s.rows().isEmpty() && s.doc() instanceof StoreDoc d
                        ? Optional.<Doc>of(snapshot(d.id(), s.rows().getFirst().data()))
                        : Optional.empty());
    }

    @Override
    public CompletableFuture<Void> index(Field<?>... fields) {
        return declare(false, fields);
    }

    @Override
    public CompletableFuture<Void> unique(Field<?> field) {
        return declare(true, field);
    }

    private CompletableFuture<Void> declare(boolean unique, Field<?>... fields) {
        List<IndexDeclaration> declarations = new ArrayList<>();
        for (Field<?> f : fields) {
            declarations.add(new IndexDeclaration(new QueryTarget.StoreTarget(ref), info(f).spec(), unique));
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (IndexDeclaration d : declarations) futures.add(registry.backend().declareIndex(d));
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    @Override
    public CachedStore cached() {
        return registry.cached(this);
    }

    @Override
    public <T> Subscription onChange(Field<T> field, FieldListener<T> listener) {
        Objects.requireNonNull(listener, "listener");
        return registry.addListener(ref, info(field), listener);
    }

    @Override
    public CompletableFuture<Boolean> transfer(Field<Long> field, String fromId, String toId, long amount) {
        FieldInfo<Long> info = info(field);
        return registry.writer().transfer(doc0(fromId), doc0(toId), info.spec(), amount)
                .thenApply(TransferResult::transferred);
    }
}
