package de.kalypzo.pantheon.audit;

import java.util.Optional;
import java.util.function.Consumer;

/** Writes audit entries about one player. A light handle without state. Thread safe. */
public interface AuditLog {

    void action(AuditAction action);

    void action(AuditAction action, Consumer<AuditDescriptor> descriptor);

    /** Logs the action and starts a trace. Ends a previous active trace without logging. */
    AuditTrace begin(AuditAction action, Consumer<AuditDescriptor> descriptor);

    Optional<AuditTrace> activeTrace();
}
