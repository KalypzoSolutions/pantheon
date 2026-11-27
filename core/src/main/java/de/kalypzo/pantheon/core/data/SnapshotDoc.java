package de.kalypzo.pantheon.core.data;

import de.kalypzo.pantheon.core.data.semantics.OpSemantics;
import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.data.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * The state of a player or store document at load time (a load, a query hit, a listener's document). Its own ops
 * update it: unconditional ones at once; for a conditional request the database applied, the namespaces of the
 * result rows are taken over and the own unconditional ops sent after the request (not contained in the rows,
 * because the writer queues them behind it) are applied again on top. Other nodes' changes do not reach it.
 */
public final class SnapshotDoc extends RemoteDocImpl implements Doc {

    private final DocView view;
    private volatile Map<String, Map<String, Object>> data; // per namespace, replaced as a whole

    // guarded by this: own unconditional ops since the oldest open conditional request
    private final List<FieldOp> log = new ArrayList<>();
    private long logStart;   // absolute index of log[0]
    private int open;        // conditional requests without a result

    private SnapshotDoc(String id, Target target, Map<String, Map<String, Object>> data) {
        super(id, target);
        this.data = Map.copyOf(data);
        this.view = new DocView(ns -> this.data.getOrDefault(ns, Map.of()));
    }

    /** From the rows of a load or a query hit. */
    public static SnapshotDoc of(String id, Target target, List<Row> rows) {
        Map<String, Map<String, Object>> data = new HashMap<>();
        for (Row row : rows) data.put(namespace(row), row.data());
        return new SnapshotDoc(id, target, data);
    }

    /** From the data of one namespace, e.g. a store document. */
    public static SnapshotDoc of(String id, Target target, String namespace, Map<String, Object> data) {
        return new SnapshotDoc(id, target, Map.of(namespace, data));
    }

    /** The current data of a namespace, empty if there is none. */
    public Map<String, Object> data(String namespace) {
        return data.getOrDefault(namespace, Map.of());
    }

    @Override
    public <T> T get(Field<T> field) {
        target().check(field);
        return view.get(field);
    }

    @Override
    public <T> Optional<T> find(Field<T> field) {
        target().check(field);
        return view.find(field);
    }

    @Override
    public boolean has(Field<?> field) {
        target().check(field);
        return view.has(field);
    }

    @Override
    protected synchronized void send(List<FieldOp> ops) {
        Map<String, Map<String, Object>> next = apply(data, ops); // throws an IAE for an invalid op, nothing changes
        super.send(ops);
        data = next;
        if (open > 0) log.addAll(ops);
    }

    @Override
    protected CompletableFuture<Boolean> conditional(List<FieldOp> ops, List<FieldCondition> conditions) {
        long mark;
        CompletableFuture<ApplyResult> sent;
        synchronized (this) { // no own op can slip in between the mark and the request's place in the queue
            mark = logStart + log.size();
            open++;
            try {
                sent = target().writeConditional(ops, conditions);
            } catch (RuntimeException e) {
                done(mark, null);
                throw e;
            }
        }
        return sent.whenComplete((r, e) -> done(mark, e == null && r.applied() ? r : null))
                .thenApply(ApplyResult::applied);
    }

    private synchronized void done(long mark, @Nullable ApplyResult applied) {
        if (applied != null) {
            Map<String, Map<String, Object>> next = new HashMap<>(data);
            for (Row row : applied.rows()) {
                String ns = namespace(row);
                Map<String, Object> rowData = row.data();
                for (int i = (int) (mark - logStart); i < log.size(); i++) {
                    FieldOp later = log.get(i);
                    if (later.field().namespace().equals(ns)) rowData = OpSemantics.apply(rowData, later);
                }
                next.put(ns, rowData);
            }
            data = Map.copyOf(next);
        }
        if (--open == 0) {
            logStart += log.size();
            log.clear();
        }
    }

    private static Map<String, Map<String, Object>> apply(Map<String, Map<String, Object>> data, List<FieldOp> ops) {
        Map<String, Map<String, Object>> next = new HashMap<>(data);
        for (FieldOp op : ops) {
            String ns = op.field().namespace();
            next.put(ns, OpSemantics.apply(next.getOrDefault(ns, Map.of()), op));
        }
        return Map.copyOf(next);
    }

    private static String namespace(Row row) {
        return switch (row.ref()) {
            case PlayerRow p -> p.namespace();
            case StoreDoc d -> d.store().namespace();
        };
    }
}
