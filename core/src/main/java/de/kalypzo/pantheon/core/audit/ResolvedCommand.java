package de.kalypzo.pantheon.core.audit;

import java.util.Set;

/** A resolved command: {@code identity} is stable per command (e.g. the Bukkit Command object). */
public record ResolvedCommand(Object identity, String name, Set<String> aliases) {}
