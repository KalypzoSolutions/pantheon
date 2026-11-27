package de.kalypzo.pantheon.data;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** An immutable query. Every method returns a new query. */
public interface Query {

    <T> Query where(Field<T> field, Condition<T> condition);

    Query sortBy(Field<?> field, SortOrder order);

    Query limit(int limit);

    CompletableFuture<List<Doc>> fetch();

    CompletableFuture<Optional<Doc>> first();

    CompletableFuture<Long> count();
}
