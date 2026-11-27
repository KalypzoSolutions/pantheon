package de.kalypzo.pantheon.paper.audit;

import de.kalypzo.pantheon.audit.AuditAction;
import de.kalypzo.pantheon.audit.AuditNamespace;
import de.kalypzo.pantheon.audit.AuditPosition;
import de.kalypzo.pantheon.audit.TimelineEntry;
import de.kalypzo.pantheon.audit.TimelinePage;
import de.kalypzo.pantheon.audit.TimelineQuery;
import de.kalypzo.pantheon.core.audit.AuditBackend;
import de.kalypzo.pantheon.core.audit.AuditRecord;
import de.kalypzo.pantheon.core.audit.StoredAuditRecord;
import de.kalypzo.pantheon.core.audit.TimelineResult;
import de.kalypzo.pantheon.core.audit.TimelineSpec;
import de.kalypzo.pantheon.core.audit.Viewer;
import de.kalypzo.pantheon.core.data.UuidV7;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * A query for one player's timeline (audit §10): {@link #fetch()} calls {@link AuditBackend#timeline} once. Without a
 * viewer it fails at once with {@code IllegalStateException("viewer fehlt")}, no call is made (E36). "No player" is the
 * nil UUID (E37). {@code actions(...)} and {@code namespace(...)} add up ({@code ns:*} for a namespace); {@code limit}
 * defaults to 100, larger values than 500 become 500 (E33). Not thread-safe: one query per caller.
 */
public final class PaperTimelineQuery implements TimelineQuery {

    /** E37: "no player". */
    public static final UUID NO_PLAYER = new UUID(0, 0);
    static final String VIA_COMMAND = "command";
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private final AuditBackend backend;
    private final UUID player;
    private @Nullable Viewer viewer;
    private @Nullable Instant from;
    private @Nullable Instant to;
    private final List<String> actions = new ArrayList<>();
    private @Nullable UUID trace;
    private int limit = DEFAULT_LIMIT;
    private @Nullable UUID cursor;

    public PaperTimelineQuery(AuditBackend backend, UUID player) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.player = Objects.requireNonNull(player, "player");
    }

    /** E37: a player gives its UUID and name, any other sender the nil UUID and its name; via {@code command}. */
    @Override
    public TimelineQuery viewer(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        return sender instanceof Player p
                ? viewer(p.getUniqueId(), p.getName(), VIA_COMMAND)
                : viewer(null, sender.getName(), VIA_COMMAND);
    }

    @Override
    public TimelineQuery viewer(@Nullable UUID player, String name, String via) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(via, "via");
        this.viewer = new Viewer(player != null ? player : NO_PLAYER, name, via);
        return this;
    }

    /** {@code to} is exclusive. */
    @Override
    public TimelineQuery between(Instant from, Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) throw new IllegalArgumentException("from is after to: " + from + " > " + to);
        this.from = from;
        this.to = to;
        return this;
    }

    @Override
    public TimelineQuery actions(AuditAction... actions) {
        for (AuditAction a : actions) this.actions.add(Objects.requireNonNull(a, "action").key());
        return this;
    }

    @Override
    public TimelineQuery namespace(AuditNamespace namespace) {
        actions.add(Objects.requireNonNull(namespace, "namespace").name() + ":*");
        return this;
    }

    @Override
    public TimelineQuery trace(UUID trace) {
        this.trace = Objects.requireNonNull(trace, "trace");
        return this;
    }

    @Override
    public TimelineQuery limit(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be at least 1: " + limit);
        this.limit = Math.min(limit, MAX_LIMIT);
        return this;
    }

    @Override
    public TimelineQuery after(UUID cursor) {
        this.cursor = Objects.requireNonNull(cursor, "cursor");
        return this;
    }

    @Override
    public CompletableFuture<TimelinePage> fetch() {
        Viewer v = viewer;
        if (v == null) throw new IllegalStateException("viewer fehlt"); // E36: no call
        TimelineSpec spec = new TimelineSpec(player, from, to, actions, limit, cursor, trace, v);
        CompletableFuture<TimelineResult> call;
        try {
            call = backend.timeline(spec);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.thenApply(PaperTimelineQuery::page);
    }

    private static TimelinePage page(TimelineResult result) {
        List<TimelineEntry> entries = result.entries().stream().map(PaperTimelineQuery::entry).toList();
        return new TimelinePage(entries, Optional.ofNullable(result.cursor()));
    }

    private static TimelineEntry entry(StoredAuditRecord stored) {
        AuditRecord r = stored.record();
        return new TimelineEntry(r.id(), UuidV7.timestamp(r.id()), r.player(), stored.server(), r.action(),
                Optional.ofNullable(r.position()).map(p -> new AuditPosition(p.world(), p.x(), p.y(), p.z())),
                Optional.ofNullable(r.target()), Optional.ofNullable(r.trace()), r.with());
    }
}
