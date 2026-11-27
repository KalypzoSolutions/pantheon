package de.kalypzo.pantheon.core.data;

import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.data.Atomic;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.RemoteDoc;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * {@link RemoteDoc}: writes without reading, for players and stores. Builds the wire ops and hands them to a
 * {@link Target}; {@link LiveDoc} and {@link SnapshotDoc} add reading on top.
 */
public class RemoteDocImpl implements RemoteDoc {

    /** Where the ops of a document go. */
    public interface Target {

        /** Unconditional: visible at once wherever the document is held, sent in the background. */
        void write(List<FieldOp> ops);

        /** Conditional: the database decides; queued behind the ops written before. */
        CompletableFuture<ApplyResult> writeConditional(List<FieldOp> ops, List<FieldCondition> conditions);

        /** Throws an IAE if {@code field} cannot be used on this document (a store's foreign namespace). */
        default void check(Field<?> field) {
            Objects.requireNonNull(field, "field");
        }
    }

    private final String id;
    private final Target target;

    public RemoteDocImpl(String id, Target target) {
        this.id = Objects.requireNonNull(id, "id");
        this.target = Objects.requireNonNull(target, "target");
    }

    /** Sends unconditional ops; subclasses may update their own state as well. */
    protected void send(List<FieldOp> ops) {
        target.write(ops);
    }

    /** Sends a conditional request; {@code true} if the database applied it. Subclasses may track the result. */
    protected CompletableFuture<Boolean> conditional(List<FieldOp> ops, List<FieldCondition> conditions) {
        return target.writeConditional(ops, conditions).thenApply(ApplyResult::applied);
    }

    protected final Target target() {
        return target;
    }

    private <T> FieldInfo<T> info(Field<T> field) {
        target.check(field);
        return FieldInfo.of(field);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public <T> void set(Field<T> field, T value) {
        send(List.of(AtomicImpl.opSet(info(field), value)));
    }

    @Override
    public void unset(Field<?> field) {
        send(List.of(new FieldOp.Unset(info(field).spec())));
    }

    @Override
    public void inc(Field<? extends Number> field, long delta) {
        send(List.of(new FieldOp.Inc(info(field).spec(), delta)));
    }

    @Override
    public <T extends Comparable<T>> void max(Field<T> field, T value) {
        FieldInfo<T> info = info(field);
        send(List.of(new FieldOp.Max(info.spec(), Objects.requireNonNull(info.encode(value), "value"))));
    }

    @Override
    public <T extends Comparable<T>> void min(Field<T> field, T value) {
        FieldInfo<T> info = info(field);
        send(List.of(new FieldOp.Min(info.spec(), Objects.requireNonNull(info.encode(value), "value"))));
    }

    @Override
    public <E> void add(Field<? extends Collection<E>> field, E element) {
        send(List.of(AtomicImpl.opAdd(info(field), element)));
    }

    @Override
    public <E> void remove(Field<? extends Collection<E>> field, E element) {
        send(List.of(AtomicImpl.opRemove(info(field), element)));
    }

    @Override
    public CompletableFuture<Boolean> withdraw(Field<Long> field, long amount) {
        return conditional(List.of(new FieldOp.Withdraw(info(field).spec(), amount)), List.of());
    }

    @Override
    public Atomic atomic() {
        return new AtomicImpl(target::check, this::conditional);
    }
}
