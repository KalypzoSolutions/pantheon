package de.kalypzo.pantheon.paper.audit;

import de.kalypzo.pantheon.audit.AuditAction;
import de.kalypzo.pantheon.audit.AuditDescriptor;
import de.kalypzo.pantheon.audit.AuditLog;
import de.kalypzo.pantheon.audit.AuditService;
import de.kalypzo.pantheon.audit.TimelineQuery;
import de.kalypzo.pantheon.core.audit.AuditBackend;
import de.kalypzo.pantheon.core.audit.AuditBuffer;
import de.kalypzo.pantheon.core.audit.EntryBuilder;
import de.kalypzo.pantheon.core.audit.HiddenCommands;
import de.kalypzo.pantheon.core.audit.TraceRegistry;
import de.kalypzo.pantheon.core.data.UuidV7;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.command.Command;
import org.jspecify.annotations.Nullable;

/**
 * The audit API of this node (audit_log.md §4, §10): every {@link AuditLog} writes into the node's one
 * {@link AuditBuffer} (§7), traces live in the node's {@link TraceRegistry}, hidden commands in its
 * {@link HiddenCommands}. It has no state of its own besides the ID generator, so the logs it hands out are light
 * handles. IDs come from a generator of its own on the runtime clock (UUIDv7, never the JVM-wide one). Thread-safe.
 */
public final class PaperAuditService implements AuditService {

    private final AuditBuffer buffer;
    private final TraceRegistry traces;
    private final HiddenCommands hidden;
    private final AuditBackend backend;
    private final Clock clock;
    private final UuidV7.Generator ids = new UuidV7.Generator();

    public PaperAuditService(AuditBuffer buffer, TraceRegistry traces, HiddenCommands hidden, AuditBackend backend,
                             Clock clock) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.traces = Objects.requireNonNull(traces, "traces");
        this.hidden = Objects.requireNonNull(hidden, "hidden");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AuditLog player(UUID player) {
        return new PaperAuditLog(this, Objects.requireNonNull(player, "player"));
    }

    /** The command object is its identity for {@link HiddenCommands} (as the {@link PaperCommandResolver} gives it). */
    @Override
    public void hideCommand(Command command) {
        hidden.hide(Objects.requireNonNull(command, "command"));
    }

    @Override
    public void hideCommand(String label) {
        hidden.hide(Objects.requireNonNull(label, "label"));
    }

    @Override
    public TimelineQuery timeline(UUID player) {
        return new PaperTimelineQuery(backend, Objects.requireNonNull(player, "player"));
    }

    TraceRegistry traces() {
        return traces;
    }

    /**
     * One entry: the descriptor fills the {@link EntryBuilder}, then the ID and the trace are taken (an explicit
     * trace wins, {@code pantheon:*} gets none), then it goes into the buffer.
     *
     * @param trace the trace the entry carries unless the descriptor names another one; null: the active trace
     */
    void log(UUID player, AuditAction action, @Nullable UUID trace, Consumer<? super AuditDescriptor> descriptor) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(descriptor, "descriptor");
        EntryBuilder entry = new EntryBuilder(player, action.key());
        if (trace != null) entry.trace(trace);
        descriptor.accept(new PaperAuditDescriptor(entry));
        log(entry);
    }

    /** A finished entry: the ID and the trace are taken (an explicit trace wins, {@code pantheon:*} gets none). */
    void log(EntryBuilder entry) {
        UUID id = ids.next(clock);
        UUID active = traces.traceFor(entry.player(), entry.action(), entry.explicitTrace());
        buffer.add(entry.build(id, active));
    }
}
