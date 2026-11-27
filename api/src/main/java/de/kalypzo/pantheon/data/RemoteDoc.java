package de.kalypzo.pantheon.data;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/** Write access to a document without reading it. */
public interface RemoteDoc {

    String id();

    // Unconditional operations: applied locally at once, executed atomically in the background
    <T> void set(Field<T> field, T value);

    void unset(Field<?> field);

    void inc(Field<? extends Number> field, long delta);

    /** Highscore: only if greater. */
    <T extends Comparable<T>> void max(Field<T> field, T value);

    /** Best time: only if smaller. */
    <T extends Comparable<T>> void min(Field<T> field, T value);

    <E> void add(Field<? extends Collection<E>> field, E element);

    <E> void remove(Field<? extends Collection<E>> field, E element);

    // Conditional operations: the database decides
    /** Only if enough is there. */
    CompletableFuture<Boolean> withdraw(Field<Long> field, long amount);

    Atomic atomic();
}
