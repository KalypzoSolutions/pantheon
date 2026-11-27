package de.kalypzo.pantheon.core.data;

import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.data.Atomic;
import de.kalypzo.pantheon.data.Condition;
import de.kalypzo.pantheon.data.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Collects ops and conditions of one document; {@code withdraw} becomes a {@link FieldOp.Withdraw}. {@link #commit}
 * sends them as one conditional request. Not thread-safe, like a builder.
 */
public final class AtomicImpl implements Atomic {

    /** Sends the collected request; {@code true} if the database applied it. */
    @FunctionalInterface
    public interface Committer {
        CompletableFuture<Boolean> commit(List<FieldOp> ops, List<FieldCondition> conditions);
    }

    private final Consumer<Field<?>> check;
    private final Committer committer;
    private final List<FieldOp> ops = new ArrayList<>();
    private final List<FieldCondition> conditions = new ArrayList<>();

    /** {@code check} throws an IAE for a field the document cannot use. */
    public AtomicImpl(Consumer<Field<?>> check, Committer committer) {
        this.check = Objects.requireNonNull(check, "check");
        this.committer = Objects.requireNonNull(committer, "committer");
    }

    private <T> FieldInfo<T> info(Field<T> field) {
        check.accept(field);
        return FieldInfo.of(field);
    }

    @Override
    public Atomic withdraw(Field<Long> field, long amount) {
        ops.add(new FieldOp.Withdraw(info(field).spec(), amount));
        return this;
    }

    @Override
    public <T> Atomic require(Field<T> field, Condition<T> condition) {
        check.accept(field);
        Objects.requireNonNull(condition, "condition");
        conditions.add(Conditions.of(field, condition));
        return this;
    }

    @Override
    public <T> Atomic set(Field<T> field, T value) {
        ops.add(opSet(info(field), value));
        return this;
    }

    @Override
    public Atomic unset(Field<?> field) {
        ops.add(new FieldOp.Unset(info(field).spec()));
        return this;
    }

    @Override
    public Atomic inc(Field<? extends Number> field, long delta) {
        ops.add(new FieldOp.Inc(info(field).spec(), delta));
        return this;
    }

    @Override
    public <E> Atomic add(Field<? extends Collection<E>> field, E element) {
        ops.add(opAdd(info(field), element));
        return this;
    }

    @Override
    public <E> Atomic remove(Field<? extends Collection<E>> field, E element) {
        ops.add(opRemove(info(field), element));
        return this;
    }

    @Override
    public CompletableFuture<Boolean> commit() {
        return committer.commit(List.copyOf(ops), List.copyOf(conditions));
    }

    // ---------- op builders, shared with RemoteDocImpl ----------

    /** {@code set(null)} is {@code unset} (E14). */
    static <T> FieldOp opSet(FieldInfo<T> info, T value) {
        return value == null ? new FieldOp.Unset(info.spec()) : new FieldOp.Set(info.spec(), info.encode(value));
    }

    static FieldOp opAdd(FieldInfo<?> info, Object element) {
        return new FieldOp.Add(info.spec(), info.encodeElement(element));
    }

    static FieldOp opRemove(FieldInfo<?> info, Object element) {
        return new FieldOp.Remove(info.spec(), info.encodeElement(element));
    }
}
