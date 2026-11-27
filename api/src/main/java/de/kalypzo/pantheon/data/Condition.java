package de.kalypzo.pantheon.data;

import java.util.Collection;
import java.util.List;

/** A condition on a field value, used by {@link Query} and {@link Atomic#require}. */
public sealed interface Condition<T> {

    record Eq<T>(T value) implements Condition<T> {}

    record Ne<T>(T value) implements Condition<T> {}

    record Gt<T extends Comparable<T>>(T value) implements Condition<T> {}

    record Gte<T extends Comparable<T>>(T value) implements Condition<T> {}

    record Lt<T extends Comparable<T>>(T value) implements Condition<T> {}

    record Lte<T extends Comparable<T>>(T value) implements Condition<T> {}

    record Contains<C extends Collection<E>, E>(E element) implements Condition<C> {}

    record In<T>(List<T> values) implements Condition<T> {
        public In {
            values = List.copyOf(values);
        }
    }

    record Exists<T>() implements Condition<T> {}

    record Not<T>(Condition<T> condition) implements Condition<T> {}

    static <T> Condition<T> eq(T value) {
        return new Eq<>(value);
    }

    static <T> Condition<T> ne(T value) {
        return new Ne<>(value);
    }

    static <T extends Comparable<T>> Condition<T> gt(T value) {
        return new Gt<>(value);
    }

    static <T extends Comparable<T>> Condition<T> gte(T value) {
        return new Gte<>(value);
    }

    static <T extends Comparable<T>> Condition<T> lt(T value) {
        return new Lt<>(value);
    }

    static <T extends Comparable<T>> Condition<T> lte(T value) {
        return new Lte<>(value);
    }

    static <C extends Collection<E>, E> Condition<C> contains(E element) {
        return new Contains<>(element);
    }

    @SafeVarargs
    static <T> Condition<T> in(T... values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("in() needs at least one value");
        }
        return new In<>(List.of(values));
    }

    static <T> Condition<T> exists() {
        return new Exists<>();
    }

    static <T> Condition<T> not(Condition<T> condition) {
        return new Not<>(condition);
    }
}
