package de.kalypzo.pantheon.core.audit;

import java.util.List;

/** Entries sent in one call, plus the number of entries lost to buffer overflow since the last bundle. */
public record AuditBundle(List<AuditRecord> entries, long dropped) {

    public AuditBundle {
        entries = List.copyOf(entries);
    }
}
