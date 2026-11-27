package de.kalypzo.pantheon.core.backend;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public sealed interface PresenceChange {

    record Upsert(PresenceEntry e) implements PresenceChange {}

    record Removed(UUID player) implements PresenceChange {}

    record Replaced(Collection<PresenceEntry> all) implements PresenceChange {
        public Replaced {
            all = List.copyOf(all);
        }
    }
}
