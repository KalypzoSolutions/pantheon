package de.kalypzo.pantheon.data;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** A collection of documents in a namespace. */
public interface Store {

    CompletableFuture<Optional<Doc>> load(String id);

    CompletableFuture<Doc> loadOrCreate(String id);

    /** Completes with {@code false} if the id already exists. */
    CompletableFuture<Boolean> create(String id, Consumer<Doc> init);

    /** Write without loading first. */
    RemoteDoc doc(String id);

    CompletableFuture<Boolean> delete(String id);

    /** Delete automatically at the given time. */
    void expire(String id, Instant at);

    Query query();

    /** Speeds up searching and sorting. */
    CompletableFuture<Void> index(Field<?>... fields);

    /** A value may occur only once. Fails with {@link DuplicateValueException} on duplicates. */
    CompletableFuture<Void> unique(Field<?> field);

    /**
     * The same store kept completely in memory, loaded once and then live. Intended for small data sets (up to a few
     * thousand documents).
     */
    CachedStore cached();

    <T> Subscription onChange(Field<T> field, FieldListener<T> listener);

    /** Subtracts from one document and adds to the other in one transaction. */
    CompletableFuture<Boolean> transfer(Field<Long> field, String fromId, String toId, long amount);
}
