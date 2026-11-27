package de.kalypzo.pantheon.paper.audit;

import de.kalypzo.pantheon.audit.AuditAction;
import de.kalypzo.pantheon.audit.AuditDescriptor;
import de.kalypzo.pantheon.audit.AuditLog;
import de.kalypzo.pantheon.audit.AuditTrace;
import de.kalypzo.pantheon.core.audit.EntryBuilder;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** The audit log of one player: a handle without state of its own (audit §4). Thread-safe. */
final class PaperAuditLog implements AuditLog {

    private final PaperAuditService service;
    private final UUID player;

    PaperAuditLog(PaperAuditService service, UUID player) {
        this.service = service;
        this.player = player;
    }

    @Override
    public void action(AuditAction action) {
        action(action, d -> {});
    }

    @Override
    public void action(AuditAction action, Consumer<AuditDescriptor> descriptor) {
        service.log(player, action, null, descriptor);
    }

    /** A finished entry of this player, e.g. from {@code ChatAudit}; same path as {@link #action}. */
    void entry(EntryBuilder entry) {
        service.log(entry);
    }

    /**
     * Starts a new trace first (ending the old one without logging), then logs the action with it. If the descriptor
     * throws, the new trace is ended again: no trace without its opening entry.
     */
    @Override
    public AuditTrace begin(AuditAction action, Consumer<AuditDescriptor> descriptor) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(descriptor, "descriptor");
        UUID trace = service.traces().begin(player);
        try {
            service.log(player, action, null, descriptor);
        } catch (RuntimeException | Error e) {
            service.traces().end(player, trace);
            throw e;
        }
        return new PaperAuditTrace(service, player, trace);
    }

    @Override
    public Optional<AuditTrace> activeTrace() {
        return service.traces().active(player).map(id -> new PaperAuditTrace(service, player, id));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PaperAuditLog other && other.player.equals(player);
    }

    @Override
    public int hashCode() {
        return player.hashCode();
    }

    @Override
    public String toString() {
        return "AuditLog[" + player + "]";
    }
}
