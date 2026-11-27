package de.kalypzo.pantheon.core.audit;

import java.util.Optional;

/** Resolves a typed command label (lower case, possibly with namespace) to the command it points to. */
/** Implementations must be thread-safe: they are called from async chat/command threads. */
@FunctionalInterface
public interface CommandResolver {

    Optional<ResolvedCommand> resolve(String label);
}
