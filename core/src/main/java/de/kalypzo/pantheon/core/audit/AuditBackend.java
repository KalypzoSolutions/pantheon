package de.kalypzo.pantheon.core.audit;

import java.util.concurrent.CompletableFuture;

/** Audit port (audit_log.md §7). */
public interface AuditBackend {

    /** Stores the bundle. Not connected: fails with {@code DataUnavailableException}. Re-sending a bundle stores nothing twice. */
    CompletableFuture<Void> append(AuditBundle bundle);

    /** Reads a timeline. An invalid spec fails with {@link IllegalArgumentException}. */
    CompletableFuture<TimelineResult> timeline(TimelineSpec spec);
}
