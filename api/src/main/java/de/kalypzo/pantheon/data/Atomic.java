package de.kalypzo.pantheon.data;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/** Several operations on one document, all or none. */
public interface Atomic {

    /** Condition: enough is there. */
    Atomic withdraw(Field<Long> field, long amount);

    <T> Atomic require(Field<T> field, Condition<T> condition);

    <T> Atomic set(Field<T> field, T value);

    Atomic unset(Field<?> field);

    Atomic inc(Field<? extends Number> field, long delta);

    <E> Atomic add(Field<? extends Collection<E>> field, E element);

    <E> Atomic remove(Field<? extends Collection<E>> field, E element);

    /** {@code true}: everything was executed, {@code false}: a condition was not met. */
    CompletableFuture<Boolean> commit();
}
