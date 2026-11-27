package de.kalypzo.pantheon.core.data;

import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.Predicate;
import de.kalypzo.pantheon.data.Condition;
import de.kalypzo.pantheon.data.Field;
import java.util.ArrayList;
import java.util.List;

/** Translates API conditions into wire predicates. */
public final class Conditions {

    private Conditions() {}

    public static <T> FieldCondition of(Field<T> field, Condition<T> condition) {
        FieldInfo<T> info = FieldInfo.of(field);
        return new FieldCondition(info.spec(), predicate(info, condition));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate predicate(FieldInfo info, Condition<?> c) {
        return switch (c) {
            case Condition.Eq<?> e -> new Predicate.Eq(info.encode(e.value()));
            case Condition.Ne<?> e -> new Predicate.Ne(info.encode(e.value()));
            case Condition.Gt<?> e -> new Predicate.Gt(required(info.encode(e.value())));
            case Condition.Gte<?> e -> new Predicate.Gte(required(info.encode(e.value())));
            case Condition.Lt<?> e -> new Predicate.Lt(required(info.encode(e.value())));
            case Condition.Lte<?> e -> new Predicate.Lte(required(info.encode(e.value())));
            case Condition.Contains<?, ?> e -> new Predicate.Contains(required(info.encodeElement(e.element())));
            case Condition.In<?> e -> {
                List<Object> values = new ArrayList<>();
                for (Object v : e.values()) values.add(info.encode(v));
                yield new Predicate.In(values);
            }
            case Condition.Exists<?> e -> new Predicate.Exists();
            case Condition.Not<?> e -> new Predicate.Not(predicate(info, e.condition()));
        };
    }

    private static Object required(Object wire) {
        if (wire == null) throw new IllegalArgumentException("condition value must not be null");
        return wire;
    }
}
