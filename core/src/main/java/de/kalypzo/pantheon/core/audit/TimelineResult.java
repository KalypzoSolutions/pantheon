package de.kalypzo.pantheon.core.audit;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One page, newest first; {@code cursor} is null on the last page. */
public record TimelineResult(List<StoredAuditRecord> entries, @Nullable UUID cursor) {

    public TimelineResult {
        entries = List.copyOf(entries);
    }
}
