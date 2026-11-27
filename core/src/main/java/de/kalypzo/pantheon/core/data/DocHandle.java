package de.kalypzo.pantheon.core.data;

import de.kalypzo.pantheon.core.data.semantics.OpSemantics;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.RowRef;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The local state of one document: last known state per namespace plus own pending ops, replayed on top
 * ({@code view = OpSemantics.applyAll(row.data, pending)}). A change event is applied only if its version is larger;
 * ops of a request whose own echo arrives (idempotency key, E4) leave {@code pending}, so they are not counted twice.
 * Thread-safe.
 */
public final class DocHandle {

    private static final Logger LOG = Logger.getLogger(DocHandle.class.getName());

    private record State(long version, Map<String, Object> data) {}

    private final DocRef ref;
    private final Object lock = new Object();
    private Map<String, State> rows = Map.of();
    private List<PendingOp> pending = List.of();

    public DocHandle(DocRef ref) {
        this.ref = ref;
    }

    /** Last known state plus replay of the pending ops. */
    public Map<String, Object> view(String namespace) {
        synchronized (lock) {
            return view(rows, pending, namespace);
        }
    }

    /** Adds ops unconditionally; they are visible immediately. Throws if an op is invalid; nothing changes then. */
    public List<RowDiff> addPending(List<FieldOp> ops) {
        synchronized (lock) {
            List<PendingOp> next = new ArrayList<>(pending);
            for (FieldOp op : ops) {
                next.add(new PendingOp(op, null));
            }
            validate(ops);
            return change(rows, next);
        }
    }

    /** Marks the given (still unsent) pending ops as part of request {@code idempotencyKey}. */
    public void markSent(String idempotencyKey, List<PendingOp> ops) {
        synchronized (lock) {
            List<PendingOp> next = new ArrayList<>(pending);
            for (PendingOp sent : ops) {
                for (int i = 0; i < next.size(); i++) {
                    PendingOp p = next.get(i);
                    if (p.requestKey() == null && p.op().equals(sent.op())) {
                        next.set(i, new PendingOp(p.op(), idempotencyKey));
                        break;
                    }
                }
            }
            pending = List.copyOf(next); // visible data does not change
        }
    }

    /**
     * The request {@code idempotencyKey} was rejected without being applied and is resent in parts (R44): its ops
     * become unsent again, in place; the visible data does not change.
     */
    public void unmark(String idempotencyKey) {
        synchronized (lock) {
            List<PendingOp> next = new ArrayList<>(pending.size());
            for (PendingOp p : pending) {
                next.add(idempotencyKey.equals(p.requestKey()) ? new PendingOp(p.op(), null) : p);
            }
            pending = List.copyOf(next);
        }
    }

    /** The request was applied: its ops leave {@code pending}, the rows are adopted if their version is larger. */
    public List<RowDiff> acked(String idempotencyKey, List<Row> acked) {
        synchronized (lock) {
            Map<String, State> nextRows = new HashMap<>(rows);
            for (Row row : acked) {
                adopt(nextRows, namespace(row.ref()), row.version(), row.data());
            }
            return change(nextRows, without(idempotencyKey));
        }
    }

    /**
     * A change event: applied if its version is larger; the ops of the event's own request in the event's namespace
     * leave {@code pending}. If a foreign event with a larger version overtakes the own echo, the view counts the ops
     * twice until the echo arrives; the end value is correct.
     */
    public List<RowDiff> accept(DocChanged change) {
        if (!belongs(change.ref())) return List.of();
        synchronized (lock) {
            Map<String, State> nextRows = new HashMap<>(rows);
            adopt(nextRows, namespace(change.ref()), change.version(), change.deleted() ? Map.of() : change.data());
            List<PendingOp> nextPending = change.idempotencyKey() == null
                    ? pending : without(change.idempotencyKey(), namespace(change.ref()));
            return change(nextRows, nextPending);
        }
    }

    /** The request was rejected (E27): its ops are dropped, the local value reverts. */
    public List<RowDiff> rejected(String idempotencyKey) {
        synchronized (lock) {
            return change(rows, without(idempotencyKey));
        }
    }

    /**
     * Takes the rows after a load or resync. A row is adopted if its version is larger than the known one; known
     * namespaces missing from {@code loaded} become empty (their version stays, so older events stay ignored).
     */
    public List<RowDiff> replaceAll(List<Row> loaded) {
        synchronized (lock) {
            Map<String, State> nextRows = new HashMap<>();
            for (Map.Entry<String, State> e : rows.entrySet()) {
                nextRows.put(e.getKey(), new State(e.getValue().version(), Map.of()));
            }
            for (Row row : loaded) {
                String ns = namespace(row.ref());
                State known = rows.get(ns);
                if (known != null && known.version() >= row.version()) {
                    nextRows.put(ns, known); // an equal or newer state is already known
                } else {
                    nextRows.put(ns, new State(row.version(), row.data()));
                }
            }
            return change(nextRows, pending);
        }
    }

    /**
     * Takes the rows of a load: a row is adopted if its version is larger than the known one; known namespaces
     * missing from {@code loaded} stay as they are. A change event that arrived while loading (a namespace created
     * meanwhile, player.md §8) is kept even if the read predates it. Player rows are never deleted, so this is
     * always correct for a load and for the reconnect resync of a player (P-22); a cached store uses
     * {@link #replaceAll}, whose documents can be deleted.
     */
    public List<RowDiff> merge(List<Row> loaded) {
        synchronized (lock) {
            Map<String, State> nextRows = new HashMap<>(rows);
            for (Row row : loaded) adopt(nextRows, namespace(row.ref()), row.version(), row.data());
            return change(nextRows, pending);
        }
    }

    /** The namespaces with a known row or pending ops. */
    public Set<String> namespaces() {
        synchronized (lock) {
            Set<String> out = new LinkedHashSet<>(rows.keySet());
            for (PendingOp p : pending) out.add(p.op().field().namespace());
            return out;
        }
    }

    /** The known version of the namespace, 0 if unknown. */
    public long version(String namespace) {
        synchronized (lock) {
            State s = rows.get(namespace);
            return s == null ? 0 : s.version();
        }
    }

    /** The pending ops in order. */
    public List<PendingOp> pending() {
        synchronized (lock) {
            return pending;
        }
    }

    // ---------- internals ----------

    private List<PendingOp> without(String key) {
        List<PendingOp> next = new ArrayList<>(pending.size());
        for (PendingOp p : pending) {
            if (!key.equals(p.requestKey())) next.add(p);
        }
        return next;
    }

    /** Drops the ops of request {@code key} in one namespace only; a request may span several, each echoed alone. */
    private List<PendingOp> without(String key, String ns) {
        List<PendingOp> next = new ArrayList<>(pending.size());
        for (PendingOp p : pending) {
            if (!(key.equals(p.requestKey()) && p.op().field().namespace().equals(ns))) next.add(p);
        }
        return next;
    }

    private static void adopt(Map<String, State> target, String ns, long version, Map<String, Object> data) {
        State known = target.get(ns);
        if (known == null || version > known.version()) {
            target.put(ns, new State(version, data));
        }
    }

    /** Commits the new state and returns the namespaces whose view changed. */
    private List<RowDiff> change(Map<String, State> nextRows, List<PendingOp> nextPending) {
        Set<String> namespaces = new LinkedHashSet<>(rows.keySet());
        namespaces.addAll(nextRows.keySet());
        for (PendingOp p : pending) namespaces.add(p.op().field().namespace());
        for (PendingOp p : nextPending) namespaces.add(p.op().field().namespace());

        List<RowDiff> diffs = new ArrayList<>();
        for (String ns : namespaces) {
            Map<String, Object> before = view(rows, pending, ns);
            Map<String, Object> after = view(nextRows, nextPending, ns);
            if (!before.equals(after)) diffs.add(new RowDiff(ns, before, after));
        }
        rows = Map.copyOf(nextRows);
        pending = List.copyOf(nextPending);
        return List.copyOf(diffs);
    }

    private static Map<String, Object> view(Map<String, State> rows, List<PendingOp> pending, String ns) {
        State s = rows.get(ns);
        Map<String, Object> data = s == null ? Map.of() : s.data();
        for (PendingOp p : pending) {
            if (!p.op().field().namespace().equals(ns)) continue;
            try {
                data = OpSemantics.apply(data, p.op());
            } catch (IllegalArgumentException e) {
                LOG.log(Level.WARNING, "Pending op does not apply to the current state and is skipped: " + p.op(), e);
            }
        }
        return data;
    }

    private static void validate(List<FieldOp> ops) {
        for (FieldOp op : ops) {
            OpSemantics.apply(Map.of(), op); // throws IllegalArgumentException for an invalid op
        }
    }

    private boolean belongs(RowRef r) {
        return switch (ref) {
            case PlayerDoc d -> r instanceof PlayerRow p && p.player().equals(d.player());
            case StoreDoc d -> d.equals(r);
        };
    }

    private String namespace(RowRef r) {
        if (!belongs(r)) throw new IllegalArgumentException("row " + r + " does not belong to " + ref);
        return switch (r) {
            case PlayerRow p -> p.namespace();
            case StoreDoc d -> d.store().namespace();
        };
    }
}
