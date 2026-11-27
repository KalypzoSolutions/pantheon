package de.kalypzo.pantheon.core.data.semantics;

import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.Predicate;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import de.kalypzo.pantheon.core.data.wire.SortSpec;
import de.kalypzo.pantheon.core.data.wire.Wire;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * In-memory semantics of operations, conditions and sorting on a document's wire data. Computes exactly what Agora's
 * Postgres SQL computes (A-05, E14, rulings R32–R36), so local application and {@code InMemoryNetwork} agree with the
 * database:
 *
 * <ul>
 *   <li>A missing field reads as its default; an op on it starts at the default and writes the result. Numeric ops
 *       without default start at 0 (R34); {@code max}/{@code min} without default take the value ({@code GREATEST}
 *       ignores {@code NULL}).
 *   <li>{@code set(null)} ≡ {@code unset}. SET {@code add} only adds what is missing, LIST {@code add} appends,
 *       {@code remove} removes all occurrences.
 *   <li>Equality is JSON equality: {@code 1 == 1.0}, deep for lists and maps; {@code eq}/{@code ne} on SET fields
 *       ignore order (R33); {@code contains} is element equality (R32).
 *   <li>Conditions are true or false, a comparison with {@code NULL} is false, {@code not(p)} is {@code !(p is true)}
 *       (R35). {@code exists}: the key is present and not {@code null}, ignoring the default.
 *   <li>Sorting is {@code NULLS LAST} in both directions, strings compare by UTF-8 bytes ({@code COLLATE "C"}).
 * </ul>
 *
 * <p>Invalid field names, values that do not fit the field type, NUL characters, non-finite doubles and overflow are
 * rejected with {@link IllegalArgumentException}, as Agora rejects them.
 */
public final class OpSemantics {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");

    private OpSemantics() {}

    /** Applies one op and returns the new data; {@code data} is not modified. {@code Withdraw} subtracts unchecked. */
    public static Map<String, Object> apply(Map<String, Object> data, FieldOp op) {
        FieldSpec f = spec(op.field());
        Map<String, Object> out = new LinkedHashMap<>(data);
        @Nullable Object value = switch (op) {
            case FieldOp.Set s -> s.value() == null ? null : requireValue(s.value(), f.type(), "set value");
            case FieldOp.Unset u -> null;
            case FieldOp.Inc i -> add(data, f, i.delta());
            case FieldOp.Withdraw w -> add(data, f, -requirePositive(w.amount()));
            case FieldOp.Max m -> extreme(data, f, m.value(), true);
            case FieldOp.Min m -> extreme(data, f, m.value(), false);
            case FieldOp.Add a -> addElement(data, f, a.element());
            case FieldOp.Remove r -> removeElement(data, f, r.element());
        };
        if (value == null) {
            out.remove(f.name());
        } else {
            out.put(f.name(), value);
        }
        return Wire.immutableMap(out);
    }

    /** Applies the ops in order. */
    public static Map<String, Object> applyAll(Map<String, Object> data, List<FieldOp> ops) {
        Map<String, Object> current = data;
        for (FieldOp op : ops) {
            current = apply(current, op);
        }
        return current;
    }

    /** Whether the condition holds; never "unknown". */
    public static boolean test(Map<String, Object> data, FieldCondition c) {
        return eval(data, spec(c.field()), c.predicate());
    }

    /** Whether the field's value (with default) is at least the amount, as Agora's implicit withdraw condition. */
    public static boolean withdrawAllowed(Map<String, Object> data, FieldOp.Withdraw w) {
        FieldSpec f = spec(w.field());
        long amount = requirePositive(w.amount());
        requireNumeric(f, "withdraw");
        Object current = read(data, f);
        if (current == null) return false;
        return f.type() == ScalarType.LONG ? toLong(current) >= amount : toDouble(current) >= (double) amount;
    }

    /** Orders by the given sorts, each {@code NULLS LAST}. Ties stay ties; the caller adds the tie-break. */
    public static Comparator<Map<String, Object>> order(List<SortSpec> sort) {
        List<SortSpec> specs = new ArrayList<>(sort.size());
        for (SortSpec s : sort) {
            FieldSpec f = spec(s.field());
            if (isJsonType(f.type())) {
                throw new IllegalArgumentException("cannot sort by " + f.type() + " field " + f.name());
            }
            specs.add(new SortSpec(f, s.descending()));
        }
        return (a, b) -> {
            for (SortSpec s : specs) {
                Object x = read(a, s.field());
                Object y = read(b, s.field());
                int c;
                if (x == null || y == null) {
                    c = x == null ? (y == null ? 0 : 1) : -1; // NULLS LAST in both directions
                } else {
                    c = compareScalar(s.field().type(), x, y);
                    if (s.descending()) c = -c;
                }
                if (c != 0) return c;
            }
            return 0;
        };
    }

    /** The field's stored value, or its default if the key is missing or {@code null}. */
    static @Nullable Object read(Map<String, Object> data, FieldSpec f) {
        Object raw = data.get(f.name());
        return raw != null ? raw : f.defaultValue();
    }

    // ---------- conditions ----------

    private static boolean eval(Map<String, Object> data, FieldSpec f, Predicate p) {
        ScalarType t = f.type();
        return switch (p) {
            case Predicate.Eq eq -> equalsValue(data, f, eq.value());
            case Predicate.Ne ne -> {
                if (ne.value() == null) yield false;
                Object v = requireValue(ne.value(), t, "comparison value");
                Object current = read(data, f);
                yield current != null && !equalsTyped(t, current, v);
            }
            case Predicate.Gt gt -> holds(compare(data, f, gt.value()), c -> c > 0);
            case Predicate.Gte gte -> holds(compare(data, f, gte.value()), c -> c >= 0);
            case Predicate.Lt lt -> holds(compare(data, f, lt.value()), c -> c < 0);
            case Predicate.Lte lte -> holds(compare(data, f, lte.value()), c -> c <= 0);
            case Predicate.Contains c -> {
                if (t != ScalarType.LIST && t != ScalarType.SET) {
                    throw new IllegalArgumentException("contains needs a LIST or SET field: " + f.name());
                }
                Object elem = c.value() == null ? null : requireJson(c.value());
                Object current = read(data, f);
                if (!(current instanceof List<?> list)) yield false; // missing or not an array, as in Agora
                for (Object e : list) {
                    if (jsonEquals(e, elem)) yield true;
                }
                yield false;
            }
            case Predicate.In in -> {
                List<Object> values = new ArrayList<>(in.values().size());
                for (Object v : in.values()) {
                    if (v != null) values.add(requireValue(v, t, "in value"));
                }
                Object current = read(data, f);
                if (current == null) yield false;
                for (Object v : values) {
                    if (equalsTyped(t, current, v)) yield true;
                }
                yield false;
            }
            case Predicate.Exists e -> data.get(f.name()) != null;
            case Predicate.Not not -> !eval(data, f, not.predicate());
        };
    }

    /** A comparison with {@code NULL} ({@code c == null}) is false. */
    private static boolean holds(@Nullable Integer c, IntPredicate test) {
        return c != null && test.test(c);
    }

    private static boolean equalsValue(Map<String, Object> data, FieldSpec f, @Nullable Object value) {
        if (value == null) return false;
        Object v = requireValue(value, f.type(), "comparison value");
        Object current = read(data, f);
        return current != null && equalsTyped(f.type(), current, v);
    }

    /** Compares the field's value (with default) with {@code value}; {@code null} if either side is null. */
    private static @Nullable Integer compare(Map<String, Object> data, FieldSpec f, @Nullable Object value) {
        if (isJsonType(f.type())) {
            throw new IllegalArgumentException("only eq/ne on " + f.type() + " field " + f.name());
        }
        if (value == null) return null;
        Object v = requireValue(value, f.type(), "comparison value");
        Object current = read(data, f);
        if (current == null) return null;
        return Integer.signum(compareScalar(f.type(), current, v));
    }

    // ---------- operations ----------

    private static Object add(Map<String, Object> data, FieldSpec f, long delta) {
        requireNumeric(f, "inc/withdraw");
        Object current = read(data, f);
        if (f.type() == ScalarType.LONG) {
            long base = current == null ? 0L : toLong(current);
            try {
                return Math.addExact(base, delta);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("long overflow on field " + f.name(), e);
            }
        }
        double result = (current == null ? 0.0 : toDouble(current)) + (double) delta;
        if (!Double.isFinite(result)) throw new IllegalArgumentException("double overflow on field " + f.name());
        return result;
    }

    private static Object extreme(Map<String, Object> data, FieldSpec f, @Nullable Object value, boolean max) {
        ScalarType t = f.type();
        if (t != ScalarType.LONG && t != ScalarType.DOUBLE && t != ScalarType.STRING) {
            throw new IllegalArgumentException("max/min needs a LONG, DOUBLE or STRING field: " + f.name());
        }
        if (value == null) throw new IllegalArgumentException("max/min value must not be null");
        Object v = requireValue(value, t, "max/min value");
        Object current = read(data, f);
        Object result = v;
        if (current != null) {
            int c = compareScalar(t, current, v);
            if (max ? c > 0 : c < 0) result = current;
        }
        return switch (t) {
            case LONG -> toLong(result);
            case DOUBLE -> toDouble(result);
            default -> result;
        };
    }

    private static List<Object> addElement(Map<String, Object> data, FieldSpec f, @Nullable Object element) {
        Object elem = requireElement(f, element);
        Object current = read(data, f);
        List<Object> out = current == null ? new ArrayList<>() : new ArrayList<>(asList(current, f));
        if (f.type() == ScalarType.SET) {
            for (Object e : out) {
                if (jsonEquals(e, elem)) return out;
            }
        }
        out.add(elem);
        return out;
    }

    private static @Nullable List<Object> removeElement(Map<String, Object> data, FieldSpec f, @Nullable Object element) {
        Object elem = requireElement(f, element);
        Object current = read(data, f);
        if (current == null) return null;
        List<Object> out = new ArrayList<>();
        for (Object e : asList(current, f)) {
            if (!jsonEquals(e, elem)) out.add(e);
        }
        return out;
    }

    // ---------- comparison ----------

    /** Typed equality: SET as an order-independent set (R33), LIST/OBJECT as JSON, scalars by value. */
    private static boolean equalsTyped(ScalarType t, Object a, Object b) {
        return switch (t) {
            case SET -> {
                List<?> x = asList(a, null);
                List<?> y = asList(b, null);
                yield containsAll(x, y) && containsAll(y, x);
            }
            case LIST, OBJECT -> jsonEquals(a, b);
            default -> compareScalar(t, a, b) == 0;
        };
    }

    private static boolean containsAll(List<?> haystack, List<?> needles) {
        outer:
        for (Object n : needles) {
            for (Object h : haystack) {
                if (jsonEquals(h, n)) continue outer;
            }
            return false;
        }
        return true;
    }

    private static int compareScalar(ScalarType t, Object a, Object b) {
        return switch (t) {
            case LONG -> Long.compare(toLong(a), toLong(b));
            case DOUBLE -> {
                double x = toDouble(a);
                double y = toDouble(b);
                yield x < y ? -1 : (x > y ? 1 : 0); // -0.0 == 0.0 as in Postgres
            }
            case BOOLEAN -> Boolean.compare(cast(a, Boolean.class), cast(b, Boolean.class));
            case STRING -> compareUtf8(cast(a, String.class), cast(b, String.class));
            default -> throw new IllegalArgumentException("not a scalar type: " + t);
        };
    }

    /** Binary string order ({@code COLLATE "C"}): unsigned UTF-8 bytes. */
    private static int compareUtf8(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** JSONB equality: numbers numerically ({@code 1 == 1.0}), lists in order, maps by key, deep. */
    static boolean jsonEquals(@Nullable Object a, @Nullable Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number x && b instanceof Number y) return decimal(x).compareTo(decimal(y)) == 0;
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) {
                if (!jsonEquals(x.get(i), y.get(i))) return false;
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !jsonEquals(e.getValue(), y.get(e.getKey()))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    /** The number as Postgres stores it in JSONB: doubles via their shortest decimal representation. */
    private static BigDecimal decimal(Number n) {
        if (n instanceof Double || n instanceof Float) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.valueOf(n.longValue());
    }

    // ---------- validation and conversion ----------

    private static FieldSpec spec(FieldSpec f) {
        if (f.name() == null || !NAME.matcher(f.name()).matches()) {
            throw new IllegalArgumentException("invalid field name: " + f.name());
        }
        if (f.defaultValue() != null) requireValue(f.defaultValue(), f.type(), "default of " + f.name());
        return f;
    }

    /** Throws unless the non-null {@code v} fits a field of type {@code t} and is valid JSON. */
    private static Object requireValue(Object v, ScalarType t, String what) {
        boolean ok = switch (t) {
            case LONG -> isIntegral(v);
            case DOUBLE -> isIntegral(v) || v instanceof Double || v instanceof Float;
            case BOOLEAN -> v instanceof Boolean;
            case STRING -> v instanceof String;
            case LIST, SET -> v instanceof List<?>;
            case OBJECT -> v instanceof Map<?, ?>;
        };
        if (!ok) throw new IllegalArgumentException(what + " " + v + " does not fit type " + t);
        return requireJson(v);
    }

    /** Throws unless the value is a representable JSON value: no NUL characters, only finite numbers. */
    private static Object requireJson(Object v) {
        switch (v) {
            case String s -> {
                if (s.indexOf('\0') >= 0) throw new IllegalArgumentException("string contains NUL character");
            }
            case Double d -> {
                if (!Double.isFinite(d)) throw new IllegalArgumentException("not a JSON number: " + d);
            }
            case Float d -> {
                if (!Float.isFinite(d)) throw new IllegalArgumentException("not a JSON number: " + d);
            }
            case List<?> list -> {
                for (Object o : list) {
                    if (o != null) requireJson(o);
                }
            }
            case Map<?, ?> map -> {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!(e.getKey() instanceof String)) throw new IllegalArgumentException("map key not a string");
                    requireJson(e.getKey());
                    if (e.getValue() != null) requireJson(e.getValue());
                }
            }
            case Boolean b -> {}
            default -> {
                if (!isIntegral(v)) throw new IllegalArgumentException("not a wire value: " + v.getClass());
            }
        }
        return v;
    }

    private static boolean isIntegral(Object v) {
        return v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte;
    }

    private static Object requireElement(FieldSpec f, @Nullable Object elem) {
        if (f.type() != ScalarType.LIST && f.type() != ScalarType.SET) {
            throw new IllegalArgumentException("add/remove needs a LIST or SET field: " + f.name());
        }
        if (elem == null) throw new IllegalArgumentException("add/remove element must not be null");
        return requireJson(elem);
    }

    private static void requireNumeric(FieldSpec f, String what) {
        if (f.type() != ScalarType.LONG && f.type() != ScalarType.DOUBLE) {
            throw new IllegalArgumentException(what + " needs a LONG or DOUBLE field: " + f.name());
        }
    }

    private static long requirePositive(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("withdraw amount must be > 0, was " + amount);
        return amount;
    }

    private static boolean isJsonType(ScalarType t) {
        return t == ScalarType.LIST || t == ScalarType.SET || t == ScalarType.OBJECT;
    }

    /** A stored or given value as long, like {@code ::bigint}; an integral double is accepted. */
    private static long toLong(Object v) {
        if (isIntegral(v)) return ((Number) v).longValue();
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 0x1p63) return d.longValue();
        throw new IllegalArgumentException("not a long value: " + v);
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("not a double value: " + v);
    }

    private static List<?> asList(Object v, @Nullable FieldSpec f) {
        if (v instanceof List<?> list) return list;
        throw new IllegalArgumentException("not a list value" + (f == null ? "" : " in field " + f.name()) + ": " + v);
    }

    private static <T> T cast(Object v, Class<T> type) {
        if (type.isInstance(v)) return type.cast(v);
        throw new IllegalArgumentException("expected " + type.getSimpleName() + ", got " + v);
    }
}
