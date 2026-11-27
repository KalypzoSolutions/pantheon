package de.kalypzo.pantheon.paper.audit;

import de.kalypzo.pantheon.audit.AuditAction;
import de.kalypzo.pantheon.audit.AuditDescriptor;
import de.kalypzo.pantheon.audit.AuditTrace;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A trace of one player on this node (audit §4). Active while it is the player's trace in the {@link
 * de.kalypzo.pantheon.core.audit.TraceRegistry}; ending it never ends a newer one. Thread-safe.
 */
final class PaperAuditTrace implements AuditTrace {

    private final PaperAuditService service;
    private final UUID player;
    private final UUID id;

    PaperAuditTrace(PaperAuditService service, UUID player, UUID id) {
        this.service = service;
        this.player = player;
        this.id = id;
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public boolean isActive() {
        return service.traces().active(player).filter(id::equals).isPresent();
    }

    @Override
    public void end() {
        service.traces().end(player, id);
    }

    /** Logs the action with this trace (also if it already ended), then ends it. */
    @Override
    public void end(AuditAction action, Consumer<AuditDescriptor> descriptor) {
        try {
            service.log(player, action, id, descriptor);
        } finally {
            end();
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PaperAuditTrace other && other.id.equals(id) && other.player.equals(player);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "AuditTrace[" + id + (isActive() ? "" : ", ended") + "]";
    }
}
