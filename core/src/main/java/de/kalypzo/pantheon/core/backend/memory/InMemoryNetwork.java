package de.kalypzo.pantheon.core.backend.memory;

import de.kalypzo.pantheon.core.audit.AuditBundle;
import de.kalypzo.pantheon.core.audit.AuditRecord;
import de.kalypzo.pantheon.core.audit.StoredAuditRecord;
import de.kalypzo.pantheon.core.audit.TimelineResult;
import de.kalypzo.pantheon.core.audit.TimelineSpec;
import de.kalypzo.pantheon.core.audit.Viewer;
import de.kalypzo.pantheon.core.backend.ArrivalHandler;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.ConnectionState;
import de.kalypzo.pantheon.core.backend.Delivered;
import de.kalypzo.pantheon.core.backend.Delivery;
import de.kalypzo.pantheon.core.backend.LocationResolver;
import de.kalypzo.pantheon.core.backend.NodeState;
import de.kalypzo.pantheon.core.backend.PresenceChange;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.data.UuidV7;
import de.kalypzo.pantheon.core.data.semantics.OpSemantics;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.CreateResult;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.DocSnapshot;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.IndexDeclaration;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.Predicate;
import de.kalypzo.pantheon.core.data.wire.QuerySpec;
import de.kalypzo.pantheon.core.data.wire.QueryTarget;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.RowRef;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import de.kalypzo.pantheon.core.data.wire.SortSpec;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.data.wire.TransferResult;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.data.DataUnavailableException;
import de.kalypzo.pantheon.data.DuplicateValueException;
import de.kalypzo.pantheon.data.Subscription;
import de.kalypzo.pantheon.player.ExpectedOnlinePlayerException;
import de.kalypzo.pantheon.world.NetworkLocation;
import de.kalypzo.pantheon.world.TeleportResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A whole network in one process: what Agora does for its nodes (agora.md §2, §5), without Postgres. For tests,
 * local development and a single server. Computes through {@link OpSemantics}, so it agrees with Agora's SQL; the
 * shared contract suite checks that.
 *
 * <ul>
 *   <li>One lock for all data, so every request is one transaction. Versions come from one shared sequence.
 *   <li>Idempotency keys are kept 24 h with their result, for every write (R22). Like Agora, an {@code apply} or
 *       {@code transfer} whose condition fails is not recorded.
 *   <li>Holders: a node holds a player from {@code loadPlayer} until {@code releasePlayer}, and a store while it watches
 *       it. Only holders get {@code DocChanged}, the writing node included. Each node delivers its events on its own
 *       single thread, in commit order.
 *   <li>Expired documents are deleted (with a {@code DocChanged}) before every call, by the injectable {@link Clock}.
 *   <li>{@link #outage()} disconnects every node: calls fail at once with {@link DataUnavailableException}, and
 *       fire-and-forget messages are lost. On {@link #restore()} a started node re-registers what its
 *       {@link NodeState} reports, like Agora's {@code Hello}.
 *   <li>{@link #outage(String)} disconnects one node only (P-22): its calls fail, its holder registrations are gone
 *       and it misses every event while the others keep writing; {@link #restore(String)} reconnects it.
 *   <li>Audit (audit_log.md §7 to §10) like Agora: entries outside −24 h/+1 h or invalid ones are dropped one by one
 *       (E29), a re-sent entry is stored once (the first write wins), {@code server} is the appending node. The first
 *       page of a timeline writes {@code pantheon:audit_view} and does not show it (R16). From its own presence the
 *       network writes {@code pantheon:join}, {@code switch} and {@code quit} with {@code reason = left} (E38); the
 *       proxy is their server. Entries are kept 14 days.
 * </ul>
 */
public final class InMemoryNetwork implements AutoCloseable {

    public static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;
    /** How long a location request waits for the server of the player (E1). */
    public static final Duration LOCATE_TIMEOUT = Duration.ofSeconds(3);
    /** Audit entries further in the past than this are dropped on append (§8). */
    public static final Duration AUDIT_MAX_AGE = Duration.ofHours(24);
    /** Audit entries further in the future than this are dropped on append (§8). */
    public static final Duration AUDIT_MAX_AHEAD = Duration.ofHours(1);
    public static final Duration AUDIT_RETENTION = Duration.ofDays(14);
    public static final Duration AUDIT_DEFAULT_RANGE = Duration.ofHours(24);
    public static final int AUDIT_DEFAULT_LIMIT = 100;
    public static final int AUDIT_MAX_LIMIT = 500;
    public static final String AUDIT_VIEW = "pantheon:audit_view";
    public static final String SESSION_JOIN = "pantheon:join";
    public static final String SESSION_SWITCH = "pantheon:switch";
    public static final String SESSION_QUIT = "pantheon:quit";

    private static final Logger LOG = Logger.getLogger(InMemoryNetwork.class.getName());
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    private static final String PANTHEON = "pantheon";
    private static final Pattern AUDIT_ACTION = Pattern.compile("[a-z0-9_]+:[a-z0-9_]+");
    private static final Pattern AUDIT_NAMESPACE = Pattern.compile("([a-z0-9_]+):\\*");
    /** "No player" (E37). */
    private static final UUID NIL = new UUID(0, 0);
    /** Largest instant whose millis fit into the 48 bit of a UUIDv7. */
    private static final long AUDIT_MAX_MILLIS = (1L << 48) - 1;
    /** A range bound that is not applied (only with a cursor, R48). */
    private static final long UNBOUNDED = -1;
    private static final Comparator<String> BINARY =
            (x, y) -> Arrays.compareUnsigned(x.getBytes(StandardCharsets.UTF_8), y.getBytes(StandardCharsets.UTF_8));
    /** Postgres orders UUIDs by their bytes, unsigned. */
    private static final Comparator<UUID> UUID_ORDER = (x, y) -> {
        int c = Long.compareUnsigned(x.getMostSignificantBits(), y.getMostSignificantBits());
        return c != 0 ? c : Long.compareUnsigned(x.getLeastSignificantBits(), y.getLeastSignificantBits());
    };

    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();
    private final Object lock = new Object();
    /** Own generator: an injected clock must not move the JVM-wide one (cross-note A-18). */
    private final UuidV7.Generator auditIds = new UuidV7.Generator();

    // everything below is guarded by lock
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<UUID, TreeMap<String, Stored>> players = new HashMap<>();
    private final Map<StoreRef, Map<String, Stored>> stores = new HashMap<>();
    private final Set<StoreDoc> expiring = new HashSet<>();
    private final Map<String, Recorded> idempotency = new HashMap<>();
    private final Map<UUID, Set<String>> playerHolders = new HashMap<>();
    private final Map<StoreRef, Set<String>> storeWatchers = new HashMap<>();
    private final Map<IndexKey, IndexDeclaration> indexes = new HashMap<>();
    private final Map<UUID, PresenceEntry> presence = new LinkedHashMap<>();
    private final Map<String, List<Delivered>> deliveries = new HashMap<>();
    private final TreeMap<UUID, StoredAuditRecord> audit = new TreeMap<>(UUID_ORDER);
    /** Entries per player and per target, for the timeline ({@code player = ? OR target = ?}). */
    private final Map<UUID, TreeMap<UUID, StoredAuditRecord>> auditTimelines = new HashMap<>();
    private boolean down;
    private int recordsSincePurge;

    public InMemoryNetwork() {
        this(Clock.systemUTC());
    }

    public InMemoryNetwork(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One node {@code local} of a new network. */
    public static Node single() {
        return single("local");
    }

    /** One node of a new network. */
    public static Node single(String nodeId) {
        return new InMemoryNetwork().node(nodeId);
    }

    /** The node with this id, created and connected on first use. */
    public Node node(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isBlank()) throw new IllegalArgumentException("node id must not be blank");
        synchronized (lock) {
            return nodes.computeIfAbsent(nodeId, Node::new);
        }
    }

    // ---------- test helpers ----------

    /** Every node loses its connection until {@link #restore()}. */
    public void outage() {
        synchronized (lock) {
            if (down) return;
            down = true;
            for (Node n : nodes.values()) n.setState(ConnectionState.DISCONNECTED);
        }
    }

    /** Nodes reconnect: they re-register their {@link NodeState} and get the current presence. */
    public void restore() {
        List<Node> current;
        synchronized (lock) {
            if (!down) return;
            current = new ArrayList<>(nodes.values());
        }
        Map<Node, NodeState> seen = new HashMap<>();
        Map<Node, Registration> registrations = new HashMap<>();
        for (Node n : current) {
            NodeState state = n.nodeState;
            seen.put(n, state);
            Registration r = Registration.of(state);
            if (r != null) registrations.put(n, r);
        }
        List<Node> changed = new ArrayList<>();
        synchronized (lock) {
            down = false;
            List<PresenceEntry> snapshot = List.copyOf(presence.values());
            for (Node n : nodes.values()) {
                if (n.partitioned) continue; // stays down until restore(nodeId)
                Registration r = registrations.get(n);
                if (r != null) {
                    unregister(n.id);
                    register(n.id, r);
                }
                // start() between the two lock sections: its state was not read above
                if (n.nodeState != null && n.nodeState != seen.get(n)) changed.add(n);
                n.setState(ConnectionState.CONNECTED);
                n.fire(n.presenceListeners, new PresenceChange.Replaced(snapshot));
            }
        }
        for (Node n : changed) {
            NodeState state = n.nodeState;
            Registration r = Registration.of(state);
            synchronized (lock) {
                // a later start() (network up again) registers itself
                if (down || n.closed || n.partitioned || n.nodeState != state || r == null) continue;
                unregister(n.id);
                register(n.id, r);
            }
        }
    }

    /**
     * Only this node loses its connection until {@link #restore(String)} (P-22): its calls fail, its fire-and-forget
     * messages are lost, it is no holder any more and misses every event, while the other nodes go on.
     */
    public void outage(String nodeId) {
        synchronized (lock) {
            Node n = nodes.get(Objects.requireNonNull(nodeId, "nodeId"));
            if (n == null || n.partitioned) return;
            n.partitioned = true;
            unregister(n.id);
            n.setState(ConnectionState.DISCONNECTED);
        }
    }

    /** The node of {@link #outage(String)} reconnects: it re-registers its {@link NodeState} and gets the presence. */
    public void restore(String nodeId) {
        Node n;
        synchronized (lock) {
            n = nodes.get(Objects.requireNonNull(nodeId, "nodeId"));
            if (n == null || !n.partitioned) return;
        }
        NodeState state = n.nodeState;
        Registration r = Registration.of(state); // outside the lock, like restore()
        synchronized (lock) {
            if (!n.partitioned || n.closed) return;
            n.partitioned = false;
            if (down) return; // the whole network is down: restore() brings the node back
            if (r != null && n.nodeState == state) {
                unregister(n.id);
                register(n.id, r);
            }
            n.setState(ConnectionState.CONNECTED);
            n.fire(n.presenceListeners, new PresenceChange.Replaced(List.copyOf(presence.values())));
        }
    }

    /** The proxy claims the player. */
    public void join(PresenceEntry entry) {
        Objects.requireNonNull(entry, "entry");
        synchronized (lock) {
            presence.put(entry.player(), entry);
            firePresence(new PresenceChange.Upsert(entry));
        }
    }

    public void switchServer(UUID player, String server) {
        synchronized (lock) {
            PresenceEntry e = presence.get(player);
            if (e == null) return;
            PresenceEntry moved = new PresenceEntry(e.player(), e.name(), server, e.proxy(), e.locale(), e.onlineSince());
            presence.put(player, moved);
            firePresence(new PresenceChange.Upsert(moved));
            auditSwitch(moved, e.server());
        }
    }

    public void leave(UUID player) {
        synchronized (lock) {
            PresenceEntry removed = presence.remove(player);
            if (removed != null) {
                firePresence(new PresenceChange.Removed(player));
                auditQuit(removed);
            }
        }
    }

    /** What the proxy received to deliver, in order. */
    public List<Delivered> deliveries(String proxyId) {
        synchronized (lock) {
            return List.copyOf(deliveries.getOrDefault(proxyId, List.of()));
        }
    }

    /** Closes every node. */
    @Override
    public void close() {
        List<Node> all;
        synchronized (lock) {
            all = new ArrayList<>(nodes.values());
        }
        for (Node n : all) n.close();
    }

    // ---------- audit ----------

    /** Under the lock. Like Agora's AuditIngest: invalid entries are dropped alone and counted like {@code dropped}. */
    private void appendAudit(String nodeId, AuditBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        Instant now = clock.instant();
        long dropped = bundle.dropped();
        for (AuditRecord r : bundle.entries()) {
            if (validAudit(r, now)) storeAudit(new StoredAuditRecord(r, nodeId));
            else dropped++;
        }
        if (dropped > 0) LOG.warning("Audit: " + dropped + " entries from " + nodeId + " dropped");
        purgeAudit(now);
    }

    private static boolean validAudit(AuditRecord r, Instant now) {
        if (r.id() == null || r.player() == null || r.action() == null) return false;
        if (!AUDIT_ACTION.matcher(r.action()).matches()) return false;
        Instant at = UuidV7.timestamp(r.id());
        if (at.isBefore(now.minus(AUDIT_MAX_AGE)) || at.isAfter(now.plus(AUDIT_MAX_AHEAD))) return false;
        // what Postgres cannot store (NUL in text/jsonb, NaN/Infinity in JSON) Agora drops as well
        if (r.position() != null && r.position().world().indexOf('\0') >= 0) return false;
        for (Map.Entry<String, Object> e : r.with().entrySet()) {
            if (e.getKey().indexOf('\0') >= 0) return false;
            if (e.getValue() instanceof String str && str.indexOf('\0') >= 0) return false;
            if (e.getValue() instanceof Double d && !Double.isFinite(d)) return false;
        }
        return true;
    }

    /** Under the lock; {@code ON CONFLICT (id) DO NOTHING}. */
    private void storeAudit(StoredAuditRecord stored) {
        AuditRecord r = stored.record();
        if (audit.putIfAbsent(r.id(), stored) != null) return;
        auditTimelines.computeIfAbsent(r.player(), k -> new TreeMap<>(UUID_ORDER)).put(r.id(), stored);
        if (r.target() != null && !r.target().equals(r.player())) {
            auditTimelines.computeIfAbsent(r.target(), k -> new TreeMap<>(UUID_ORDER)).put(r.id(), stored);
        }
    }

    private void purgeAudit(Instant now) {
        Map<UUID, StoredAuditRecord> old = audit.headMap(UuidV7.lowerBound(now.minus(AUDIT_RETENTION)), false);
        if (old.isEmpty()) return;
        for (StoredAuditRecord s : old.values()) {
            unindex(s.record().player(), s.record().id());
            if (s.record().target() != null) unindex(s.record().target(), s.record().id());
        }
        old.clear();
    }

    private void unindex(UUID player, UUID id) {
        TreeMap<UUID, StoredAuditRecord> t = auditTimelines.get(player);
        if (t == null) return;
        t.remove(id);
        if (t.isEmpty()) auditTimelines.remove(player);
    }

    /** Under the lock. Like Agora's TimelineService, including the {@code audit_view} of the first page. */
    private TimelineResult timeline(String nodeId, TimelineSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (spec.player() == null) throw new IllegalArgumentException("player fehlt");
        Viewer viewer = spec.viewer();
        if (viewer == null) throw new IllegalArgumentException("viewer fehlt");
        if (viewer.name() == null || viewer.name().isEmpty()) throw new IllegalArgumentException("viewer.name fehlt");
        long fromMillis = millis(spec.from(), "from");
        long toMillis = millis(spec.to(), "to");

        // R26: 0 = unset; `to` defaults to now, `from` to `to` - 24 h. R48: with a cursor an unset bound stays open.
        long to;
        long from;
        if (spec.cursor() != null) {
            to = toMillis != 0 ? toMillis : UNBOUNDED;
            from = fromMillis != 0 ? fromMillis : UNBOUNDED;
        } else {
            to = toMillis != 0 ? toMillis : clock.millis();
            from = fromMillis != 0 ? fromMillis : Math.max(0, to - AUDIT_DEFAULT_RANGE.toMillis());
        }

        Set<String> exact = new HashSet<>();
        List<String> prefixes = new ArrayList<>();
        for (String a : spec.actions()) {
            var ns = AUDIT_NAMESPACE.matcher(a);
            if (AUDIT_ACTION.matcher(a).matches()) exact.add(a);
            else if (ns.matches()) prefixes.add(ns.group(1) + ":");
            else throw new IllegalArgumentException("ungültiges Aktionsmuster: " + a);
        }
        long rawLimit = Integer.toUnsignedLong(spec.limit()); // uint32 on the wire
        int limit = rawLimit == 0 ? AUDIT_DEFAULT_LIMIT : (int) Math.min(rawLimit, AUDIT_MAX_LIMIT);

        UUID exclude = null;
        if (spec.cursor() == null) {
            exclude = auditIds.next(clock);
            storeAudit(new StoredAuditRecord(auditView(exclude, spec, viewer, from, to), nodeId));
        }

        UUID lower = from == UNBOUNDED ? null : UuidV7.lowerBound(Instant.ofEpochMilli(from));
        UUID upper = to == UNBOUNDED ? null : UuidV7.lowerBound(Instant.ofEpochMilli(to));
        if (spec.cursor() != null && (upper == null || UUID_ORDER.compare(spec.cursor(), upper) < 0)) {
            upper = spec.cursor();
        }
        List<StoredAuditRecord> page = new ArrayList<>();
        TreeMap<UUID, StoredAuditRecord> all = auditTimelines.get(spec.player());
        if (all != null && (lower == null || upper == null || UUID_ORDER.compare(lower, upper) < 0)) {
            NavigableMap<UUID, StoredAuditRecord> range = all;
            if (upper != null) range = range.headMap(upper, false);
            if (lower != null) range = range.tailMap(lower, true);
            for (StoredAuditRecord s : range.descendingMap().values()) {
                AuditRecord r = s.record();
                if (r.id().equals(exclude)) continue; // R16
                if (!(exact.isEmpty() && prefixes.isEmpty()) && !exact.contains(r.action())
                        && prefixes.stream().noneMatch(r.action()::startsWith)) continue;
                if (spec.trace() != null && !spec.trace().equals(r.trace())) continue;
                page.add(s);
                if (page.size() == limit) break;
            }
        }
        UUID cursor = page.size() == limit ? page.getLast().record().id() : null;
        return new TimelineResult(page, cursor);
    }

    private static AuditRecord auditView(UUID id, TimelineSpec spec, Viewer viewer, long from, long to) {
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("viewer_name", viewer.name());
        with.put("via", Objects.requireNonNullElse(viewer.via(), ""));
        with.put("from", from);
        with.put("to", to);
        if (!spec.actions().isEmpty()) with.put("actions", String.join(",", spec.actions()));
        if (spec.trace() != null) with.put("trace", spec.trace().toString());
        UUID player = viewer.player() != null ? viewer.player() : NIL; // E37
        return new AuditRecord(id, player, AUDIT_VIEW, null, spec.player(), null, with);
    }

    /** Epoch millis of a bound; null and the epoch itself mean "not set" (0, R26). */
    private static long millis(@Nullable Instant t, String field) {
        if (t == null) return 0;
        long m;
        try {
            m = t.toEpochMilli();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(field + " außerhalb des Bereichs", e);
        }
        if (m < 0 || m > AUDIT_MAX_MILLIS) throw new IllegalArgumentException(field + " außerhalb des Bereichs");
        return m;
    }

    /** Under the lock: {@code join} or {@code switch} after a server change (like Agora's SessionAuditor). */
    private void auditSwitch(PresenceEntry moved, @Nullable String previous) {
        String server = moved.server();
        if (server == null || server.isEmpty() || server.equals(previous)) return;
        Map<String, Object> with = new LinkedHashMap<>();
        boolean first = previous == null || previous.isEmpty();
        if (!first) with.put("from", previous);
        with.put("to", server);
        auditSession(moved, first ? SESSION_JOIN : SESSION_SWITCH, with);
    }

    /** Under the lock: {@code quit} after a leave; without a server there was no join and there is no quit. */
    private void auditQuit(PresenceEntry removed) {
        if (removed.server() == null || removed.server().isEmpty()) return;
        auditSession(removed, SESSION_QUIT, Map.of("from", removed.server(), "reason", "left"));
    }

    private void auditSession(PresenceEntry entry, String action, Map<String, Object> with) {
        storeAudit(new StoredAuditRecord(
                new AuditRecord(auditIds.next(clock), entry.player(), action, null, null, null, with), entry.proxy()));
    }

    // ---------- reads ----------

    private List<Row> loadPlayer(Node node, UUID player) {
        Objects.requireNonNull(player, "player");
        playerHolders.computeIfAbsent(player, k -> new LinkedHashSet<>()).add(node.id);
        return rowsOf(player);
    }

    private List<Row> load(DocRef doc) {
        return switch (Objects.requireNonNull(doc, "doc")) {
            case PlayerDoc p -> rowsOf(Objects.requireNonNull(p.player(), "player"));
            case StoreDoc d -> {
                Stored s = stored(validDoc(d));
                yield s == null ? List.of() : List.of(new Row(d, s.version(), s.data()));
            }
        };
    }

    private List<DocSnapshot> query(QueryTarget target, QuerySpec spec) {
        Objects.requireNonNull(spec, "spec");
        int limit = spec.limit() == 0 ? DEFAULT_LIMIT : spec.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT + " (0 = " + DEFAULT_LIMIT + ")");
        }
        if (spec.offset() < 0) throw new IllegalArgumentException("offset must not be negative");
        return switch (Objects.requireNonNull(target, "target")) {
            case QueryTarget.StoreTarget t -> {
                StoreRef store = t.store();
                Comparator<Map.Entry<String, Stored>> order = Comparator.comparing(
                        (Map.Entry<String, Stored> e) -> e.getValue().data(), storeOrder(store, spec.sort()))
                        .thenComparing(Map.Entry::getKey, BINARY);
                yield storeHits(store, spec.where()).stream().sorted(order).skip(spec.offset()).limit(limit)
                        .map(e -> new DocSnapshot(new StoreDoc(store, e.getKey()),
                                List.of(new Row(new StoreDoc(store, e.getKey()), e.getValue().version(),
                                        e.getValue().data()))))
                        .toList();
            }
            case QueryTarget.Players p -> playerHits(spec.where()).stream()
                    .sorted(playerOrder(spec.sort())).skip(spec.offset()).limit(limit)
                    .map(id -> new DocSnapshot(new PlayerDoc(id), rowsOf(id)))
                    .toList();
        };
    }

    private long count(QueryTarget target, List<FieldCondition> where) {
        Objects.requireNonNull(where, "where");
        return switch (Objects.requireNonNull(target, "target")) {
            case QueryTarget.StoreTarget t -> storeHits(t.store(), where).size();
            case QueryTarget.Players p -> playerHits(where).size();
        };
    }

    private Map<RowRef, Long> versions(Collection<RowRef> rows, Collection<UUID> forPlayers) {
        Map<RowRef, Long> out = new LinkedHashMap<>();
        for (RowRef ref : rows) {
            Stored s = stored(validRef(ref));
            out.put(ref, s == null ? 0L : s.version());
        }
        for (UUID p : forPlayers) {
            for (Row r : rowsOf(Objects.requireNonNull(p, "player"))) out.put(r.ref(), r.version());
        }
        return out;
    }

    private List<Map.Entry<String, Stored>> storeHits(StoreRef store, List<FieldCondition> where) {
        validStore(store);
        for (FieldCondition c : where) {
            requireStoreField(store, c.field());
            validCondition(c);
        }
        List<Map.Entry<String, Stored>> hits = new ArrayList<>();
        for (Map.Entry<String, Stored> e : stores.getOrDefault(store, Map.of()).entrySet()) {
            if (matches(e.getValue().data(), where)) hits.add(e);
        }
        return hits;
    }

    /** Players with a {@code pantheon} row; a condition on a missing namespace row sees an empty row (E9). */
    private List<UUID> playerHits(List<FieldCondition> where) {
        for (FieldCondition c : where) {
            requireNamespace(c.field().namespace());
            validCondition(c);
        }
        List<UUID> hits = new ArrayList<>();
        for (Map.Entry<UUID, TreeMap<String, Stored>> e : players.entrySet()) {
            if (!e.getValue().containsKey(PANTHEON)) continue;
            boolean all = true;
            for (FieldCondition c : where) {
                if (!OpSemantics.test(dataOf(e.getValue(), c.field().namespace()), c)) {
                    all = false;
                    break;
                }
            }
            if (all) hits.add(e.getKey());
        }
        return hits;
    }

    private Comparator<Map<String, Object>> storeOrder(StoreRef store, List<SortSpec> sort) {
        for (SortSpec s : sort) requireStoreField(store, s.field());
        return OpSemantics.order(sort);
    }

    private Comparator<UUID> playerOrder(List<SortSpec> sort) {
        Comparator<UUID> order = (x, y) -> 0;
        for (SortSpec s : sort) {
            String ns = requireNamespace(s.field().namespace());
            Comparator<Map<String, Object>> byField = OpSemantics.order(List.of(s));
            order = order.thenComparing(id -> dataOf(players.get(id), ns), byField);
        }
        return order.thenComparing(UUID_ORDER);
    }

    // ---------- writes ----------

    private ApplyResult apply(WriteRequest r) {
        Objects.requireNonNull(r, "request");
        String key = requireKey(r.idempotencyKey());
        DocRef doc = Objects.requireNonNull(r.doc(), "doc");
        ApplyResult replay = replay(key, ApplyResult.class);
        if (replay != null) return replay;

        Map<String, List<FieldOp>> ops = new TreeMap<>();
        for (FieldOp op : r.ops()) {
            ops.computeIfAbsent(rowNamespace(doc, op.field()), k -> new ArrayList<>()).add(op);
        }
        Map<String, List<FieldCondition>> conditions = new TreeMap<>();
        for (FieldCondition c : r.conditions()) {
            conditions.computeIfAbsent(rowNamespace(doc, c.field()), k -> new ArrayList<>()).add(validCondition(c));
        }
        for (Map.Entry<String, List<FieldOp>> e : ops.entrySet()) {
            conditions.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(implicitConditions(e.getValue()));
        }
        for (Map.Entry<String, List<FieldCondition>> e : conditions.entrySet()) {
            Map<String, Object> data = dataOf(rowRef(doc, e.getKey()));
            if (!matches(data, e.getValue())) return new ApplyResult(false, List.of());
        }
        Map<RowRef, Map<String, Object>> pending = new LinkedHashMap<>();
        for (Map.Entry<String, List<FieldOp>> e : ops.entrySet()) {
            RowRef ref = rowRef(doc, e.getKey());
            pending.put(ref, OpSemantics.applyAll(dataOf(ref), e.getValue()));
        }
        ApplyResult result = new ApplyResult(true, commit(pending, key));
        record(key, result);
        return result;
    }

    private CreateResult create(String idempotencyKey, StoreDoc doc, Map<String, Object> data) {
        String key = requireKey(idempotencyKey);
        validDoc(doc);
        CreateResult replay = replay(key, CreateResult.class);
        if (replay != null) return replay;
        Map<String, Object> clean = createData(doc.store().namespace(), data);
        CreateResult result;
        if (stored(doc) != null) {
            result = new CreateResult(false, null);
        } else {
            result = new CreateResult(true, commit(Map.of(doc, clean), key).getFirst());
        }
        record(key, result);
        return result;
    }

    private boolean delete(String idempotencyKey, StoreDoc doc) {
        String key = requireKey(idempotencyKey);
        validDoc(doc);
        Boolean replay = replay(key, Boolean.class);
        if (replay != null) return replay;
        boolean deleted = remove(doc, key);
        record(key, deleted);
        return deleted;
    }

    private void expire(String idempotencyKey, StoreDoc doc, Instant at) {
        String key = requireKey(idempotencyKey);
        validDoc(doc);
        Objects.requireNonNull(at, "at");
        if (replay(key, Done.class) != null) return;
        Stored s = stored(doc);
        if (s != null) {
            stores.get(doc.store()).put(doc.id(), new Stored(s.version(), s.data(), at));
            expiring.add(doc);
        }
        record(key, Done.EXPIRED);
        purgeExpired();
    }

    private TransferResult transfer(String idempotencyKey, DocRef from, DocRef to, FieldSpec field, long amount) {
        String key = requireKey(idempotencyKey);
        Objects.requireNonNull(field, "field");
        if (amount <= 0) throw new IllegalArgumentException("transfer amount must be > 0, was " + amount);
        if (field.type() != ScalarType.LONG && field.type() != ScalarType.DOUBLE) {
            throw new IllegalArgumentException("transfer needs a LONG or DOUBLE field: " + field.name());
        }
        RowRef fromRef = rowRef(from, rowNamespace(Objects.requireNonNull(from, "from"), validField(field)));
        RowRef toRef = rowRef(to, rowNamespace(Objects.requireNonNull(to, "to"), field));
        if (fromRef.equals(toRef)) throw new IllegalArgumentException("transfer to the same document");
        TransferResult replay = replay(key, TransferResult.class);
        if (replay != null) return replay;

        FieldOp.Withdraw withdraw = new FieldOp.Withdraw(field, amount);
        Map<String, Object> fromData = dataOf(fromRef);
        if (!OpSemantics.withdrawAllowed(fromData, withdraw)) return new TransferResult(false, List.of());
        Map<RowRef, Map<String, Object>> pending = new LinkedHashMap<>();
        pending.put(fromRef, OpSemantics.apply(fromData, withdraw));
        pending.put(toRef, OpSemantics.apply(dataOf(toRef), new FieldOp.Inc(field, amount)));
        TransferResult result = new TransferResult(true, commit(pending, key));
        record(key, result);
        return result;
    }

    private void declareIndex(IndexDeclaration index) {
        Objects.requireNonNull(index, "index");
        FieldSpec f = validField(Objects.requireNonNull(index.field(), "field"));
        switch (Objects.requireNonNull(index.target(), "target")) {
            case QueryTarget.StoreTarget t -> requireStoreField(validStore(t.store()), f);
            case QueryTarget.Players p -> requireNamespace(f.namespace());
        }
        IndexKey key = new IndexKey(index.target(), f.namespace(), f.name());
        if (index.unique()) {
            List<Map<String, Object>> rows = new ArrayList<>(scope(index, Map.of()).values());
            for (int i = 0; i < rows.size(); i++) {
                Object v = valueOf(rows.get(i), f);
                if (v == null) continue;
                FieldCondition eq = new FieldCondition(f, new Predicate.Eq(v));
                for (int j = i + 1; j < rows.size(); j++) {
                    if (valueOf(rows.get(j), f) != null && OpSemantics.test(rows.get(j), eq)) {
                        indexes.remove(key); // a failed build leaves no index (R40)
                        throw new DuplicateValueException(indexName(index));
                    }
                }
            }
        }
        indexes.put(key, index);
    }

    /**
     * Agora's implicit {@code withdraw} conditions (UpdateBuilder.implicitConditions): preceding {@code inc} and
     * {@code withdraw} on the same field are folded in, so each condition refers to the stored value (base: stored
     * value, else default, else 0, R38). A {@code withdraw} after any other operation on the same field, or amounts
     * that overflow a long, are rejected.
     */
    private static List<FieldCondition> implicitConditions(List<FieldOp> rowOps) {
        List<FieldCondition> out = new ArrayList<>();
        Map<String, Long> delta = new HashMap<>();
        Set<String> tainted = new HashSet<>();
        try {
            for (FieldOp op : rowOps) {
                String name = op.field().name();
                switch (op) {
                    case FieldOp.Inc inc -> delta.merge(name, inc.delta(), Math::addExact);
                    case FieldOp.Withdraw w -> {
                        if (w.amount() <= 0) {
                            throw new IllegalArgumentException("withdraw amount must be > 0, was " + w.amount());
                        }
                        if (tainted.contains(name)) {
                            throw new IllegalArgumentException("withdraw after a non-additive operation on field " + name);
                        }
                        long before = delta.getOrDefault(name, 0L);
                        long required = Math.subtractExact(w.amount(), before);
                        FieldSpec f = w.field();
                        if (f.defaultValue() == null) f = new FieldSpec(f.namespace(), f.name(), f.type(), 0L);
                        out.add(new FieldCondition(f, new Predicate.Gte(required)));
                        delta.put(name, Math.subtractExact(before, w.amount()));
                    }
                    default -> tainted.add(name);
                }
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("inc/withdraw amounts overflow a long", e);
        }
        return out;
    }

    /** Checks unique indexes, then writes every row with a new version and notifies the holders. */
    private List<Row> commit(Map<RowRef, Map<String, Object>> pending, String key) {
        checkUnique(pending);
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<RowRef, Map<String, Object>> e : pending.entrySet()) {
            RowRef ref = e.getKey();
            Stored old = stored(ref);
            Stored s = new Stored(sequence.incrementAndGet(), e.getValue(), old == null ? null : old.expiresAt());
            switch (ref) {
                case PlayerRow pr -> players.computeIfAbsent(pr.player(), k -> new TreeMap<>()).put(pr.namespace(), s);
                case StoreDoc d -> stores.computeIfAbsent(d.store(), k -> new HashMap<>()).put(d.id(), s);
            }
            rows.add(new Row(ref, s.version(), s.data()));
            emit(new DocChanged(ref, s.version(), false, s.data(), key));
        }
        return rows;
    }

    private boolean remove(StoreDoc doc, @Nullable String key) {
        Map<String, Stored> docs = stores.get(doc.store());
        if (docs == null || docs.remove(doc.id()) == null) return false;
        expiring.remove(doc);
        emit(new DocChanged(doc, sequence.incrementAndGet(), true, Map.of(), key));
        return true;
    }

    private void purgeExpired() {
        if (expiring.isEmpty()) return;
        Instant now = clock.instant();
        List<StoreDoc> due = new ArrayList<>();
        for (StoreDoc d : expiring) {
            Stored s = stored(d);
            if (s == null || s.expiresAt() == null || !s.expiresAt().isAfter(now)) due.add(d);
        }
        for (StoreDoc d : due) {
            expiring.remove(d);
            Stored s = stored(d);
            if (s != null && s.expiresAt() != null && !s.expiresAt().isAfter(now)) remove(d, null);
        }
    }

    private void checkUnique(Map<RowRef, Map<String, Object>> pending) {
        for (Map.Entry<RowRef, Map<String, Object>> e : pending.entrySet()) {
            for (IndexDeclaration index : indexes.values()) {
                if (!index.unique() || !covers(index, e.getKey())) continue;
                Object v = valueOf(e.getValue(), index.field());
                if (v == null) continue;
                FieldCondition eq = new FieldCondition(index.field(), new Predicate.Eq(v));
                for (Map.Entry<RowRef, Map<String, Object>> other : scope(index, pending).entrySet()) {
                    if (!other.getKey().equals(e.getKey()) && valueOf(other.getValue(), index.field()) != null
                            && OpSemantics.test(other.getValue(), eq)) {
                        throw new DuplicateValueException(indexName(index));
                    }
                }
            }
        }
    }

    private static boolean covers(IndexDeclaration index, RowRef ref) {
        return switch (index.target()) {
            case QueryTarget.StoreTarget t -> ref instanceof StoreDoc d && d.store().equals(t.store());
            case QueryTarget.Players p -> ref instanceof PlayerRow r && r.namespace().equals(index.field().namespace());
        };
    }

    /** All rows an index covers, with {@code pending} replacing the stored state. */
    private Map<RowRef, Map<String, Object>> scope(IndexDeclaration index, Map<RowRef, Map<String, Object>> pending) {
        Map<RowRef, Map<String, Object>> out = new LinkedHashMap<>();
        switch (index.target()) {
            case QueryTarget.StoreTarget t -> {
                for (Map.Entry<String, Stored> e : stores.getOrDefault(t.store(), Map.of()).entrySet()) {
                    out.put(new StoreDoc(t.store(), e.getKey()), e.getValue().data());
                }
            }
            case QueryTarget.Players p -> {
                String ns = index.field().namespace();
                for (Map.Entry<UUID, TreeMap<String, Stored>> e : players.entrySet()) {
                    Stored s = e.getValue().get(ns);
                    if (s != null) out.put(new PlayerRow(e.getKey(), ns), s.data());
                }
            }
        }
        for (Map.Entry<RowRef, Map<String, Object>> e : pending.entrySet()) {
            if (covers(index, e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private static String indexName(IndexDeclaration index) {
        return switch (index.target()) {
            case QueryTarget.StoreTarget t ->
                    "pantheon_doc_" + t.store().qualified().replace(':', '_') + "_" + index.field().name();
            case QueryTarget.Players p ->
                    "pantheon_player_" + index.field().namespace() + "_" + index.field().name();
        };
    }

    /** The data of a {@code create}: wire values by field name; {@code null} means absent. */
    private static Map<String, Object> createData(String namespace, Map<String, Object> data) {
        Objects.requireNonNull(data, "data");
        List<FieldOp> sets = new ArrayList<>();
        try {
            for (Map.Entry<String, Object> e : data.entrySet()) {
                if (e.getValue() == null) continue;
                sets.add(new FieldOp.Set(new FieldSpec(namespace, e.getKey(), typeOf(e.getValue()), null), e.getValue()));
            }
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("not a wire value: " + e.getMessage(), e);
        }
        return OpSemantics.applyAll(Map.of(), sets);
    }

    private static ScalarType typeOf(Object v) {
        return switch (v) {
            case Boolean b -> ScalarType.BOOLEAN;
            case Long l -> ScalarType.LONG;
            case Integer i -> ScalarType.LONG;
            case Short s -> ScalarType.LONG;
            case Byte b -> ScalarType.LONG;
            case Double d -> ScalarType.DOUBLE;
            case Float f -> ScalarType.DOUBLE;
            case String s -> ScalarType.STRING;
            case List<?> l -> ScalarType.LIST;
            case Map<?, ?> m -> ScalarType.OBJECT;
            default -> throw new IllegalArgumentException("not a wire value: " + v.getClass().getName());
        };
    }

    // ---------- idempotency ----------

    private <T> @Nullable T replay(String key, Class<T> type) {
        Recorded r = idempotency.get(key);
        if (r == null) return null;
        if (r.at().plus(IDEMPOTENCY_RETENTION).isBefore(clock.instant())) {
            idempotency.remove(key);
            return null;
        }
        if (!type.isInstance(r.result())) {
            throw new IllegalArgumentException("idempotency key " + key + " was used for a different operation");
        }
        return type.cast(r.result());
    }

    private void record(String key, Object result) {
        Instant now = clock.instant();
        idempotency.put(key, new Recorded(result, now));
        if (++recordsSincePurge >= 1024) {
            recordsSincePurge = 0;
            idempotency.values().removeIf(r -> r.at().plus(IDEMPOTENCY_RETENTION).isBefore(now));
        }
    }

    // ---------- rows ----------

    private List<Row> rowsOf(UUID player) {
        TreeMap<String, Stored> rows = players.get(player);
        if (rows == null) return List.of();
        List<Row> out = new ArrayList<>(rows.size());
        for (Map.Entry<String, Stored> e : rows.entrySet()) {
            out.add(new Row(new PlayerRow(player, e.getKey()), e.getValue().version(), e.getValue().data()));
        }
        return out;
    }

    private @Nullable Stored stored(RowRef ref) {
        return switch (ref) {
            case PlayerRow r -> {
                TreeMap<String, Stored> rows = players.get(r.player());
                yield rows == null ? null : rows.get(r.namespace());
            }
            case StoreDoc d -> stores.getOrDefault(d.store(), Map.of()).get(d.id());
        };
    }

    private Map<String, Object> dataOf(RowRef ref) {
        Stored s = stored(ref);
        return s == null ? Map.of() : s.data();
    }

    private static Map<String, Object> dataOf(@Nullable Map<String, Stored> rows, String namespace) {
        Stored s = rows == null ? null : rows.get(namespace);
        return s == null ? Map.of() : s.data();
    }

    private static RowRef rowRef(DocRef doc, String namespace) {
        return switch (doc) {
            case PlayerDoc p -> new PlayerRow(Objects.requireNonNull(p.player(), "player"), namespace);
            case StoreDoc d -> d;
        };
    }

    private static boolean matches(Map<String, Object> data, List<FieldCondition> conditions) {
        for (FieldCondition c : conditions) {
            if (!OpSemantics.test(data, c)) return false;
        }
        return true;
    }

    /**
     * The explicitly stored value as a unique index sees it (R43: partial index over rows that have the field), so
     * the default never collides; {@code null} if the field is not set.
     */
    private static @Nullable Object valueOf(Map<String, Object> data, FieldSpec f) {
        return data.get(f.name());
    }

    // ---------- validation ----------

    /** The namespace of the row the field lives in; store fields must belong to the store's namespace. */
    private static String rowNamespace(DocRef doc, FieldSpec field) {
        Objects.requireNonNull(field, "field");
        if (doc instanceof StoreDoc d) {
            validDoc(d);
            requireStoreField(d.store(), field);
        }
        return requireNamespace(field.namespace());
    }

    private static void requireStoreField(StoreRef store, FieldSpec field) {
        if (!Objects.equals(field.namespace(), store.namespace())) {
            throw new IllegalArgumentException("field " + field.namespace() + "." + field.name()
                    + " does not belong to store " + store.qualified());
        }
    }

    private static String requireNamespace(String namespace) {
        if (namespace == null || !NAME.matcher(namespace).matches()) {
            throw new IllegalArgumentException("invalid namespace: " + namespace);
        }
        return namespace;
    }

    private static StoreRef validStore(StoreRef store) {
        Objects.requireNonNull(store, "store");
        requireNamespace(store.namespace());
        if (store.name() == null || !NAME.matcher(store.name()).matches()) {
            throw new IllegalArgumentException("invalid store name: " + store.name());
        }
        return store;
    }

    private static StoreDoc validDoc(StoreDoc doc) {
        Objects.requireNonNull(doc, "doc");
        validStore(doc.store());
        if (doc.id() == null || doc.id().isEmpty() || doc.id().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid document id: " + doc.id());
        }
        return doc;
    }

    private static RowRef validRef(RowRef ref) {
        switch (Objects.requireNonNull(ref, "ref")) {
            case PlayerRow r -> {
                Objects.requireNonNull(r.player(), "player");
                requireNamespace(r.namespace());
            }
            case StoreDoc d -> validDoc(d);
        }
        return ref;
    }

    private static FieldSpec validField(FieldSpec f) {
        requireNamespace(f.namespace());
        OpSemantics.test(Map.of(), new FieldCondition(f, new Predicate.Exists())); // name, type and default
        return f;
    }

    private static FieldCondition validCondition(FieldCondition c) {
        Objects.requireNonNull(c, "condition");
        Objects.requireNonNull(c.predicate(), "predicate");
        validField(Objects.requireNonNull(c.field(), "field"));
        OpSemantics.test(Map.of(), c); // rejects values that do not fit the field
        return c;
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("idempotency key missing");
        return key;
    }

    // ---------- events ----------

    private void emit(DocChanged event) {
        Set<String> holders = switch (event.ref()) {
            case PlayerRow r -> playerHolders.getOrDefault(r.player(), Set.of());
            case StoreDoc d -> storeWatchers.getOrDefault(d.store(), Set.of());
        };
        for (String id : holders) {
            Node n = nodes.get(id);
            if (n != null && !n.partitioned) n.fire(n.docListeners, event);
        }
    }

    private void firePresence(PresenceChange change) {
        if (down) return;
        for (Node n : nodes.values()) {
            if (!n.partitioned) n.fire(n.presenceListeners, change);
        }
    }

    private void register(String nodeId, Registration r) {
        for (UUID p : r.players()) playerHolders.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(nodeId);
        for (StoreRef s : r.stores()) storeWatchers.computeIfAbsent(s, k -> new LinkedHashSet<>()).add(nodeId);
    }

    private void unregister(String nodeId) {
        playerHolders.values().forEach(s -> s.remove(nodeId));
        storeWatchers.values().forEach(s -> s.remove(nodeId));
    }

    // ---------- types ----------

    private record Stored(long version, Map<String, Object> data, @Nullable Instant expiresAt) {}

    private record Recorded(Object result, Instant at) {}

    private record IndexKey(QueryTarget target, String namespace, String name) {}

    private enum Done { EXPIRED }

    private record Registration(List<UUID> players, List<StoreRef> stores) {
        static @Nullable Registration of(@Nullable NodeState state) {
            if (state == null) return null;
            return new Registration(List.copyOf(state.heldPlayers()), List.copyOf(state.watchedStores()));
        }
    }

    /** One node of the network; implements every port. */
    public final class Node implements Backend {

        private final String id;
        private final ExecutorService events;
        private final List<Consumer<DocChanged>> docListeners = new CopyOnWriteArrayList<>();
        private final List<Consumer<PresenceChange>> presenceListeners = new CopyOnWriteArrayList<>();
        private final List<Consumer<ConnectionState>> stateListeners = new CopyOnWriteArrayList<>();
        private volatile @Nullable NodeState nodeState;
        private volatile @Nullable LocationResolver resolver;
        private volatile @Nullable ArrivalHandler arrivals;
        private volatile ConnectionState state;
        private boolean closed; // guarded by lock
        private boolean partitioned; // guarded by lock; outage(nodeId)

        private Node(String id) {
            this.id = id;
            this.events = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pantheon-memory-" + id);
                t.setDaemon(true);
                return t;
            });
            this.state = down ? ConnectionState.DISCONNECTED : ConnectionState.CONNECTED;
        }

        public String nodeId() {
            return id;
        }

        /** The network this node belongs to, e.g. for presence in memory mode (R12). */
        public InMemoryNetwork network() {
            return InMemoryNetwork.this;
        }

        // ----- Backend -----

        @Override
        public void start(NodeState state) {
            Registration r = Registration.of(Objects.requireNonNull(state, "state"));
            synchronized (lock) {
                this.nodeState = state;
                if (!offline() && r != null) register(id, r);
            }
        }

        @Override
        public ConnectionState state() {
            return state;
        }

        @Override
        public Subscription onConnectionState(Consumer<ConnectionState> listener) {
            return subscribe(stateListeners, listener);
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (closed) return;
                closed = true;
                nodes.remove(id, this);
                unregister(id);
                setState(ConnectionState.DISCONNECTED);
            }
            events.shutdown();
        }

        // ----- DataBackend -----

        @Override
        public CompletableFuture<List<Row>> loadPlayer(UUID player) {
            return call(() -> InMemoryNetwork.this.loadPlayer(this, player));
        }

        @Override
        public void releasePlayer(UUID player) {
            synchronized (lock) {
                if (offline()) return; // lost, like a stream message
                Set<String> holders = playerHolders.get(player);
                if (holders != null) holders.remove(id);
            }
        }

        @Override
        public CompletableFuture<List<Row>> load(DocRef doc) {
            return call(() -> InMemoryNetwork.this.load(doc));
        }

        @Override
        public CompletableFuture<List<DocSnapshot>> query(QueryTarget target, QuerySpec spec) {
            return call(() -> InMemoryNetwork.this.query(target, spec));
        }

        @Override
        public CompletableFuture<Long> count(QueryTarget target, List<FieldCondition> where) {
            return call(() -> InMemoryNetwork.this.count(target, where));
        }

        @Override
        public CompletableFuture<Map<RowRef, Long>> versions(Collection<RowRef> rows, Collection<UUID> players) {
            return call(() -> InMemoryNetwork.this.versions(rows, players));
        }

        @Override
        public CompletableFuture<ApplyResult> apply(WriteRequest request) {
            return call(() -> InMemoryNetwork.this.apply(request));
        }

        @Override
        public CompletableFuture<CreateResult> create(String idempotencyKey, StoreDoc doc, Map<String, Object> data) {
            return call(() -> InMemoryNetwork.this.create(idempotencyKey, doc, data));
        }

        @Override
        public CompletableFuture<Boolean> delete(String idempotencyKey, StoreDoc doc) {
            return call(() -> InMemoryNetwork.this.delete(idempotencyKey, doc));
        }

        @Override
        public CompletableFuture<Void> expire(String idempotencyKey, StoreDoc doc, Instant at) {
            return call(() -> {
                InMemoryNetwork.this.expire(idempotencyKey, doc, at);
                return null;
            });
        }

        @Override
        public CompletableFuture<TransferResult> transfer(String idempotencyKey, DocRef from, DocRef to,
                                                          FieldSpec field, long amount) {
            return call(() -> InMemoryNetwork.this.transfer(idempotencyKey, from, to, field, amount));
        }

        @Override
        public CompletableFuture<Void> declareIndex(IndexDeclaration index) {
            return call(() -> {
                InMemoryNetwork.this.declareIndex(index);
                return null;
            });
        }

        @Override
        public void watchStore(StoreRef store) {
            validStore(store);
            synchronized (lock) {
                if (offline()) return;
                storeWatchers.computeIfAbsent(store, k -> new LinkedHashSet<>()).add(id);
            }
        }

        @Override
        public void unwatchStore(StoreRef store) {
            synchronized (lock) {
                if (offline()) return;
                Set<String> watchers = storeWatchers.get(store);
                if (watchers != null) watchers.remove(id);
            }
        }

        @Override
        public Subscription onDocChanged(Consumer<DocChanged> listener) {
            return subscribe(docListeners, listener);
        }

        // ----- PresenceBackend -----

        @Override
        public Collection<PresenceEntry> snapshot() {
            synchronized (lock) {
                return List.copyOf(presence.values());
            }
        }

        @Override
        public Subscription onPresenceChanged(Consumer<PresenceChange> listener) {
            return subscribe(presenceListeners, listener);
        }

        @Override
        public CompletableFuture<NetworkLocation> location(UUID player) {
            LocationResolver target;
            synchronized (lock) {
                if (offline()) return CompletableFuture.failedFuture(unavailable());
                PresenceEntry e = presence.get(player);
                Node server = e == null || e.server() == null ? null : nodes.get(e.server());
                target = server == null ? null : server.resolver;
            }
            if (target == null) {
                return CompletableFuture.failedFuture(
                        new ExpectedOnlinePlayerException(player, ExpectedOnlinePlayerException.Scope.NETWORK));
            }
            CompletableFuture<Optional<NetworkLocation>> answer;
            try {
                answer = target.resolve(player);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            // E1: Agora waits at most 3 s for the server of the player; no answer means not reachable
            return answer.copy().orTimeout(LOCATE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).handle((found, error) -> {
                if (error instanceof CompletionException ce && ce.getCause() != null) error = ce.getCause();
                if (error instanceof TimeoutException) throw offline(player);
                if (error != null) throw error instanceof RuntimeException re ? re : new CompletionException(error);
                return found.orElseThrow(() -> offline(player));
            });
        }

        private ExpectedOnlinePlayerException offline(UUID player) {
            return new ExpectedOnlinePlayerException(player, ExpectedOnlinePlayerException.Scope.NETWORK);
        }

        @Override
        public void answerLocations(LocationResolver resolver) {
            this.resolver = Objects.requireNonNull(resolver, "resolver");
        }

        /** shortcut: no proxy hook yet, so every teleport over the network is SERVER_UNAVAILABLE; T-7 replaces it. */
        @Override
        public CompletableFuture<TeleportResult> teleport(UUID player, String server,
                                                          @Nullable NetworkLocation location) {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(server, "server");
            synchronized (lock) {
                if (offline()) return CompletableFuture.completedFuture(TeleportResult.NETWORK_UNAVAILABLE);
            }
            return CompletableFuture.completedFuture(TeleportResult.SERVER_UNAVAILABLE);
        }

        @Override
        public void answerArrivals(ArrivalHandler handler) {
            this.arrivals = Objects.requireNonNull(handler, "handler");
        }

        @Override
        public void reportArrival(String requestId, boolean arrived) {
            Objects.requireNonNull(requestId, "requestId"); // nothing waits for it before T-7
        }

        // ----- MessageBackend -----

        @Override
        public void deliver(UUID player, Delivery delivery) {
            Objects.requireNonNull(delivery, "delivery");
            synchronized (lock) {
                if (offline()) return;
                PresenceEntry e = presence.get(player);
                if (e == null) return; // offline: dropped
                deliveries.computeIfAbsent(e.proxy(), k -> new ArrayList<>()).add(new Delivered(player, delivery));
            }
        }

        // ----- AuditBackend -----

        @Override
        public CompletableFuture<Void> append(AuditBundle bundle) {
            return call(() -> {
                appendAudit(id, bundle);
                return null;
            });
        }

        @Override
        public CompletableFuture<TimelineResult> timeline(TimelineSpec spec) {
            return call(() -> InMemoryNetwork.this.timeline(id, spec));
        }

        // ----- internals -----

        /** Runs the body as one transaction; fails at once when disconnected. */
        private <T> CompletableFuture<T> call(Supplier<T> body) {
            try {
                synchronized (lock) {
                    if (offline()) throw unavailable();
                    purgeExpired();
                    return CompletableFuture.completedFuture(body.get());
                }
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        private DataUnavailableException unavailable() {
            return new DataUnavailableException("node " + id + " is not connected");
        }

        /** Under the network lock. */
        private boolean offline() {
            return down || closed || partitioned;
        }

        /** Called under the network lock, so the order of events is the commit order. */
        private void setState(ConnectionState next) {
            if (state == next) return;
            state = next;
            fire(stateListeners, next);
        }

        private <E> void fire(List<Consumer<E>> listeners, E event) {
            if (listeners.isEmpty()) return;
            try {
                events.execute(() -> {
                    for (Consumer<E> l : listeners) {
                        try {
                            l.accept(event);
                        } catch (RuntimeException e) {
                            LOG.log(Level.WARNING, "Listener of node " + id + " failed", e);
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                // node closed
            }
        }

        private <E> Subscription subscribe(List<Consumer<E>> listeners, Consumer<E> listener) {
            Objects.requireNonNull(listener, "listener");
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
    }
}
