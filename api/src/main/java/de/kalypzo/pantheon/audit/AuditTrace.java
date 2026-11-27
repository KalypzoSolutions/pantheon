package de.kalypzo.pantheon.audit;

import java.util.UUID;
import java.util.function.Consumer;

/** Connects related entries of one player on one node. */
public interface AuditTrace {

    UUID id();

    boolean isActive();

    /** Ends the trace without logging. */
    void end();

    /** Logs the action with this trace, then ends it. */
    void end(AuditAction action, Consumer<AuditDescriptor> descriptor);
}
