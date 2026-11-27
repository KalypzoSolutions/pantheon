package de.kalypzo.pantheon.core.audit;

import de.kalypzo.pantheon.audit.AuditAction;
import de.kalypzo.pantheon.audit.AuditDescriptor;
import de.kalypzo.pantheon.audit.AuditLog;
import de.kalypzo.pantheon.audit.AuditTrace;
import java.util.Optional;
import java.util.function.Consumer;

/** For tests whose PanPlayers do not log: an audit log that drops everything. */
public final class NoAudit implements AuditLog {

    public static final AuditLog INSTANCE = new NoAudit();

    private NoAudit() {}

    @Override
    public void action(AuditAction action) {}

    @Override
    public void action(AuditAction action, Consumer<AuditDescriptor> descriptor) {}

    @Override
    public AuditTrace begin(AuditAction action, Consumer<AuditDescriptor> descriptor) {
        throw new UnsupportedOperationException("no traces in this test");
    }

    @Override
    public Optional<AuditTrace> activeTrace() {
        return Optional.empty();
    }
}
