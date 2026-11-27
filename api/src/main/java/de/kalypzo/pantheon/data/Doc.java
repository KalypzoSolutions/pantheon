package de.kalypzo.pantheon.data;

import java.util.Optional;

/** A document: an id and values for any fields. */
public interface Doc extends RemoteDoc {

    <T> T get(Field<T> field);

    <T> Optional<T> find(Field<T> field);

    boolean has(Field<?> field);
}
