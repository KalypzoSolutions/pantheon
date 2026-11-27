package de.kalypzo.pantheon.core.audit;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One audit entry (audit_log.md §2). {@code id} is a UUIDv7 and carries the time. {@code with} is unmodifiable. */
public record AuditRecord(UUID id, UUID player, String action, @Nullable Position position, @Nullable UUID target,
                          @Nullable UUID trace, Map<String, Object> with) {

    public AuditRecord {
        with = Map.copyOf(with);
    }
}
