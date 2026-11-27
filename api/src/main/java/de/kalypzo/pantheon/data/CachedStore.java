package de.kalypzo.pantheon.data;

import java.util.Collection;
import java.util.Optional;

/** A store kept completely in memory. */
public interface CachedStore extends Store {

    Optional<Doc> get(String id);

    Collection<Doc> all();
}
