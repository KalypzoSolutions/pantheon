package de.kalypzo.pantheon.core.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A timeline query; {@code to} is exclusive, {@code limit} is capped at 500 by the backend (E33). */
public record TimelineSpec(UUID player, @Nullable Instant from, @Nullable Instant to, List<String> actions,
                           int limit, @Nullable UUID cursor, @Nullable UUID trace, Viewer viewer) {

    public TimelineSpec {
        actions = List.copyOf(actions);
    }
}
