package de.kalypzo.pantheon.core.data;

import de.kalypzo.pantheon.core.backend.DataBackend;
import de.kalypzo.pantheon.core.data.wire.DocSnapshot;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.QuerySpec;
import de.kalypzo.pantheon.core.data.wire.QueryTarget;
import de.kalypzo.pantheon.core.data.wire.SortSpec;
import de.kalypzo.pantheon.data.Condition;
import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.Query;
import de.kalypzo.pantheon.data.SortOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An immutable query (data.md §5): every method returns a new query. {@code limit} 1..1000, default 100 (E6). Sorting
 * by a SET, LIST or OBJECT field is rejected. Hits are mapped by {@code hit}, typically to {@link SnapshotDoc}s.
 */
public final class QueryImpl implements Query {

    public static final int MAX_LIMIT = 1000;

    private final DataBackend backend;
    private final QueryTarget target;
    private final Consumer<Field<?>> check;
    private final Function<DocSnapshot, Optional<Doc>> hit;
    private final List<FieldCondition> where;
    private final List<SortSpec> sort;
    private final int limit; // 0 = backend default

    /** {@code check} throws an IAE for a field the target cannot use (a store's foreign namespace). */
    public QueryImpl(DataBackend backend, QueryTarget target, Consumer<Field<?>> check,
                     Function<DocSnapshot, Optional<Doc>> hit) {
        this(backend, target, check, hit, List.of(), List.of(), 0);
    }

    private QueryImpl(DataBackend backend, QueryTarget target, Consumer<Field<?>> check,
                      Function<DocSnapshot, Optional<Doc>> hit, List<FieldCondition> where, List<SortSpec> sort,
                      int limit) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.target = Objects.requireNonNull(target, "target");
        this.check = Objects.requireNonNull(check, "check");
        this.hit = Objects.requireNonNull(hit, "hit");
        this.where = List.copyOf(where);
        this.sort = List.copyOf(sort);
        this.limit = limit;
    }

    @Override
    public <T> Query where(Field<T> field, Condition<T> condition) {
        check.accept(field);
        Objects.requireNonNull(condition, "condition");
        List<FieldCondition> next = new ArrayList<>(where);
        next.add(Conditions.of(field, condition));
        return new QueryImpl(backend, target, check, hit, next, sort, limit);
    }

    @Override
    public Query sortBy(Field<?> field, SortOrder order) {
        check.accept(field);
        Objects.requireNonNull(order, "order");
        switch (field.type()) {
            case SET, LIST, OBJECT -> throw new IllegalArgumentException(
                    "Cannot sort by " + field + ": " + field.type() + " fields have no order");
            default -> { }
        }
        List<SortSpec> next = new ArrayList<>(sort);
        next.add(new SortSpec(FieldInfo.of(field).spec(), order == SortOrder.DESC));
        return new QueryImpl(backend, target, check, hit, where, next, limit);
    }

    @Override
    public Query limit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT + ", was " + limit);
        }
        return new QueryImpl(backend, target, check, hit, where, sort, limit);
    }

    @Override
    public CompletableFuture<List<Doc>> fetch() {
        return run(limit);
    }

    @Override
    public CompletableFuture<Optional<Doc>> first() {
        return run(1).thenApply(docs -> docs.isEmpty() ? Optional.empty() : Optional.of(docs.getFirst()));
    }

    @Override
    public CompletableFuture<Long> count() {
        return backend.count(target, where);
    }

    private CompletableFuture<List<Doc>> run(int limit) {
        return backend.query(target, new QuerySpec(where, sort, limit, 0)).thenApply(snapshots -> {
            List<Doc> docs = new ArrayList<>(snapshots.size());
            for (DocSnapshot s : snapshots) hit.apply(s).ifPresent(docs::add);
            return List.copyOf(docs);
        });
    }
}
