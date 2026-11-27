package de.kalypzo.pantheon.core.data.semantics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.Predicate;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import de.kalypzo.pantheon.core.data.wire.SortSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * In-memory semantics of ops, conditions and sorting (E14). Same test names and values as Agora's
 * {@code SqlSemanticsTest} (A-05), plus the A-05 review rulings R32–R36.
 */
class OpSemanticsTest {

    // ---------- the card's cases (mirrors SqlSemanticsTest) ----------

    @Test
    void incUsesDefaultWhenMissing() {
        FieldSpec coins = field("coins", ScalarType.LONG, 1L);
        assertThat(read(apply(Map.of(), new FieldOp.Inc(coins, 2)), coins)).isEqualTo(3L);
    }

    @Test
    void incKeepsLongExact() {
        FieldSpec coins = field("coins", ScalarType.LONG, 0L);
        Map<String, Object> data = apply(Map.of("coins", Long.MAX_VALUE - 1), new FieldOp.Inc(coins, 1));
        assertThat(read(data, coins)).isEqualTo(Long.MAX_VALUE);
        assertThat(data).isEqualTo(Map.of("coins", Long.MAX_VALUE));
    }

    @Test
    void withdrawConditionFailsBelowAmount() {
        FieldSpec coins = field("coins", ScalarType.LONG, 0L);
        assertThat(apply(Map.of("coins", 50L), new FieldOp.Withdraw(coins, 60))).isNull();
        assertThat(read(apply(Map.of("coins", 50L), new FieldOp.Withdraw(coins, 50)), coins)).isEqualTo(0L);
        assertThat(apply(Map.of(), new FieldOp.Withdraw(coins, 1))).isNull();
    }

    @Test
    void maxOnlyRaises() {
        FieldSpec level = field("level", ScalarType.LONG, 1L);
        assertThat(read(apply(Map.of("level", 5L), new FieldOp.Max(level, 3L)), level)).isEqualTo(5L);
        assertThat(read(apply(Map.of("level", 5L), new FieldOp.Max(level, 7L)), level)).isEqualTo(7L);
    }

    @Test
    void minUsesValueWhenNoDefault() {
        FieldSpec best = field("best", ScalarType.LONG);
        assertThat(apply(Map.of(), new FieldOp.Min(best, 4L))).isEqualTo(Map.of("best", 4L));
    }

    @Test
    void setAddIgnoresDuplicate() {
        FieldSpec kits = field("kits", ScalarType.SET, List.of());
        assertThat(apply(Map.of("kits", List.of("a")), new FieldOp.Add(kits, "a"))).isEqualTo(Map.of("kits", List.of("a")));
        assertThat(apply(Map.of(), new FieldOp.Add(kits, "a"))).isEqualTo(Map.of("kits", List.of("a")));
    }

    @Test
    void listAddAppendsDuplicate() {
        FieldSpec log = field("log", ScalarType.LIST, List.of());
        assertThat(apply(Map.of("log", List.of("a")), new FieldOp.Add(log, "a")))
                .isEqualTo(Map.of("log", List.of("a", "a")));
    }

    @Test
    void removeRemovesAll() {
        FieldSpec log = field("log", ScalarType.LIST, List.of());
        assertThat(apply(Map.of("log", List.of("a", "b", "a")), new FieldOp.Remove(log, "a")))
                .isEqualTo(Map.of("log", List.of("b")));
        FieldSpec noDefault = field("log", ScalarType.LIST);
        assertThat(apply(Map.of(), new FieldOp.Remove(noDefault, "a"))).isEqualTo(Map.of());
    }

    @Test
    void setNullUnsets() {
        FieldSpec home = field("home", ScalarType.STRING);
        assertThat(apply(Map.of("home", "x", "k", 1L), new FieldOp.Set(home, null))).isEqualTo(Map.of("k", 1L));
        assertThat(apply(Map.of("home", "x", "k", 1L), new FieldOp.Unset(home))).isEqualTo(Map.of("k", 1L));
    }

    @Test
    void neIsFalseOnNull() {
        FieldSpec level = field("level", ScalarType.LONG);
        assertThat(test(Map.of(), level, new Predicate.Ne(5L))).isFalse();
        assertThat(test(Map.of("level", 4L), level, new Predicate.Ne(5L))).isTrue();
    }

    @Test
    void notIsTrueOnNull() {
        FieldSpec level = field("level", ScalarType.LONG);
        Predicate eq5 = new Predicate.Eq(5L);
        Predicate not = new Predicate.Not(eq5);
        assertThat(test(Map.of(), level, eq5)).isFalse();
        assertThat(test(Map.of(), level, not)).isTrue();
        assertThat(test(Map.of("level", 5L), level, not)).isFalse();
    }

    @Test
    void containsUsesDefaultEmptyArray() {
        FieldSpec kits = field("kits", ScalarType.SET, List.of());
        Predicate containsA = new Predicate.Contains("a");
        assertThat(read(Map.of(), kits)).isEqualTo(List.of());
        assertThat(test(Map.of(), kits, containsA)).isFalse();
        assertThat(test(Map.of(), kits, new Predicate.Not(containsA))).isTrue();
        FieldSpec withA = field("kits", ScalarType.SET, List.of("a"));
        assertThat(test(Map.of(), withA, containsA)).isTrue();
        assertThat(test(Map.of("kits", List.of("b")), withA, containsA)).isFalse();
    }

    @Test
    void stringCompareIsBinary() {
        FieldSpec name = field("name", ScalarType.STRING);
        assertThat(test(Map.of("name", "B"), name, new Predicate.Lt("a"))).isTrue();
        assertThat(sortedIds(List.of(Map.of("name", "a"), Map.of("name", "B")), List.of(new SortSpec(name, false))))
                .containsExactly(1, 0);
    }

    @Test
    void multipleOpsOnSameFieldApplyInOrder() {
        FieldSpec coins = field("coins", ScalarType.LONG, 0L);
        assertThat(read(apply(Map.of("coins", 100L), new FieldOp.Set(coins, 5L), new FieldOp.Inc(coins, 2)), coins))
                .isEqualTo(7L);
    }

    @Test
    void rejectsInvalidFieldName() {
        FieldSpec bad = field("coins'--", ScalarType.LONG);
        assertThatThrownBy(() -> OpSemantics.apply(Map.of(), new FieldOp.Inc(bad, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.test(Map.of(), new FieldCondition(bad, new Predicate.Eq(1L))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.order(List.of(new SortSpec(bad, false))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- additional cases (mirrors SqlSemanticsTest) ----------

    @Test
    void withdrawRequiresPositiveAmount() {
        FieldSpec coins = field("coins", ScalarType.LONG, 0L);
        assertThatThrownBy(() -> OpSemantics.apply(Map.of(), new FieldOp.Withdraw(coins, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.withdrawAllowed(Map.of(), new FieldOp.Withdraw(coins, -5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consecutiveWithdrawsAreCheckedTogether() {
        FieldSpec coins = field("coins", ScalarType.LONG, 0L);
        assertThat(apply(Map.of("coins", 50L), new FieldOp.Withdraw(coins, 30), new FieldOp.Withdraw(coins, 30)))
                .isNull();
        assertThat(read(apply(Map.of("coins", 60L), new FieldOp.Withdraw(coins, 30), new FieldOp.Withdraw(coins, 30)),
                        coins))
                .isEqualTo(0L);
    }

    @Test
    void defaultsAreSafeTypedLiterals() {
        String evil = "it's \\' ? -- /* $$ \"x\"";
        FieldSpec s = field("s", ScalarType.STRING, evil);
        assertThat(read(Map.of(), s)).isEqualTo(evil);
        FieldSpec obj = field("o", ScalarType.OBJECT, Map.of("q", evil));
        assertThat(read(Map.of(), obj)).isEqualTo(Map.of("q", evil));
        FieldSpec min = field("m", ScalarType.LONG, Long.MIN_VALUE);
        assertThat(read(Map.of(), min)).isEqualTo(Long.MIN_VALUE);
        FieldSpec d = field("d", ScalarType.DOUBLE, -1.5e300);
        assertThat(read(Map.of(), d)).isEqualTo(-1.5e300);
        FieldSpec b = field("b", ScalarType.BOOLEAN, true);
        assertThat(read(Map.of(), b)).isEqualTo(true);
        assertThat(read(Map.of("b", false), b)).isEqualTo(false);
    }

    @Test
    void rejectsDefaultOfWrongType() {
        assertThatThrownBy(() -> OpSemantics.apply(Map.of(), new FieldOp.Inc(field("coins", ScalarType.LONG, "1"), 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.test(Map.of(),
                        new FieldCondition(field("s", ScalarType.STRING, "a\u0000b"), new Predicate.Eq("x"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sortIsNullsLastWithIdTieBreak() {
        FieldSpec level = field("level", ScalarType.LONG);
        List<Map<String, Object>> rows = List.of(Map.of("level", 2L), Map.of(), Map.of("level", 5L), Map.of("level", 2L));
        assertThat(sortedIds(rows, List.of(new SortSpec(level, false)))).containsExactly(0, 3, 2, 1);
        assertThat(sortedIds(rows, List.of(new SortSpec(level, true)))).containsExactly(2, 0, 3, 1);
    }

    @Test
    void inMatchesListedValues() {
        FieldSpec level = field("level", ScalarType.LONG, 1L);
        Predicate in = new Predicate.In(List.of(1L, 3L));
        assertThat(test(Map.of(), level, in)).isTrue();
        assertThat(test(Map.of("level", 2L), level, in)).isFalse();
        assertThat(test(Map.of(), level, new Predicate.In(List.of()))).isFalse();
    }

    @Test
    void existsIgnoresDefault() {
        FieldSpec level = field("level", ScalarType.LONG, 1L);
        Predicate exists = new Predicate.Exists();
        assertThat(test(Map.of(), level, exists)).isFalse();
        assertThat(test(Map.of("level", 1L), level, exists)).isTrue();
    }

    // ---------- P-06 card ----------

    @Test
    void rejectsNonPositiveWithdraw() {
        FieldSpec coins = field("coins", ScalarType.LONG, 0L);
        for (long amount : new long[] {0, -1, Long.MIN_VALUE}) {
            FieldOp.Withdraw w = new FieldOp.Withdraw(coins, amount);
            assertThatThrownBy(() -> OpSemantics.apply(Map.of("coins", 10L), w))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OpSemantics.withdrawAllowed(Map.of("coins", 10L), w))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void withdrawApplyDoesNotCheck() {
        FieldSpec coins = field("coins", ScalarType.LONG, 0L);
        assertThat(OpSemantics.apply(Map.of("coins", 10L), new FieldOp.Withdraw(coins, 30)))
                .isEqualTo(Map.of("coins", -20L));
        assertThat(OpSemantics.withdrawAllowed(Map.of("coins", 10L), new FieldOp.Withdraw(coins, 30))).isFalse();
        assertThat(OpSemantics.withdrawAllowed(Map.of("coins", 30L), new FieldOp.Withdraw(coins, 30))).isTrue();
    }

    @Test
    void applyReturnsNewMapAndKeepsInput() {
        FieldSpec coins = field("coins", ScalarType.LONG, 0L);
        Map<String, Object> input = new HashMap<>(Map.of("coins", 1L, "other", "x"));
        Map<String, Object> out = OpSemantics.apply(input, new FieldOp.Inc(coins, 1));
        assertThat(input).isEqualTo(Map.of("coins", 1L, "other", "x"));
        assertThat(out).isEqualTo(Map.of("coins", 2L, "other", "x"));
        assertThat(out).isNotSameAs(input);
    }

    @Test
    void stringCompareUsesUtf8ByteOrder() {
        // U+FFFF (EF BF BF) sorts before U+1F600 (F0 ..) in UTF-8, although its UTF-16 char is larger.
        FieldSpec name = field("name", ScalarType.STRING);
        String bmp = "￿";
        String emoji = "😀";
        assertThat(bmp.compareTo(emoji)).isPositive();
        assertThat(test(Map.of("name", bmp), name, new Predicate.Lt(emoji))).isTrue();
        assertThat(sortedIds(List.of(Map.of("name", emoji), Map.of("name", bmp)), List.of(new SortSpec(name, false))))
                .containsExactly(1, 0);
        assertThat(read(apply(Map.of("name", emoji), new FieldOp.Min(name, bmp)), name)).isEqualTo(bmp);
    }

    @Test
    void jsonNumbersCompareNumerically() {
        FieldSpec obj = field("o", ScalarType.OBJECT);
        assertThat(test(Map.of("o", Map.of("a", 1L)), obj, new Predicate.Eq(Map.of("a", 1.0)))).isTrue();
        FieldSpec kits = field("kits", ScalarType.SET, List.of());
        assertThat(apply(Map.of("kits", List.of(1L)), new FieldOp.Add(kits, 1.0))).isEqualTo(Map.of("kits", List.of(1L)));
        FieldSpec log = field("log", ScalarType.LIST, List.of());
        assertThat(apply(Map.of("log", List.of(1L, 2L, 1.0)), new FieldOp.Remove(log, 1L)))
                .isEqualTo(Map.of("log", List.of(2L)));
        assertThat(test(Map.of("log", List.of(1.0)), log, new Predicate.Contains(1L))).isTrue();
        FieldSpec d = field("d", ScalarType.DOUBLE);
        assertThat(test(Map.of("d", 2L), d, new Predicate.Eq(2.0))).isTrue();
        assertThat(test(Map.of("d", 2.5), d, new Predicate.Gt(2L))).isTrue();
        assertThat(test(Map.of("o", Map.of("a", "1")), obj, new Predicate.Eq(Map.of("a", 1L)))).isFalse();
    }

    /** R32: contains is deep element equality, not Postgres {@code @>} containment of nested elements. */
    @Test
    void containsUsesDeepElementEquality() {
        FieldSpec sets = field("sets", ScalarType.LIST, List.of());
        Map<String, Object> data = Map.of("sets", List.of(List.of(1L, 2L), Map.of("a", 1L, "b", 2L)));
        assertThat(test(data, sets, new Predicate.Contains(List.of(1L)))).isFalse();
        assertThat(test(data, sets, new Predicate.Contains(Map.of("a", 1L)))).isFalse();
        assertThat(test(data, sets, new Predicate.Contains(List.of(1L, 2L)))).isTrue();
        assertThat(test(data, sets, new Predicate.Contains(List.of(2L, 1L)))).isFalse();
        assertThat(test(data, sets, new Predicate.Contains(Map.of("b", 2.0, "a", 1L)))).isTrue();
        // Agora's values
        FieldSpec k = field("k", ScalarType.SET, List.of());
        Map<String, Object> nested = Map.of("k", List.of(List.of(1L, 2L), Map.of("a", 1L, "b", 2L), 3L));
        assertThat(test(nested, k, new Predicate.Contains(List.of(1L)))).isFalse();
        assertThat(test(nested, k, new Predicate.Contains(List.of(1L, 2L)))).isTrue();
        assertThat(test(nested, k, new Predicate.Contains(Map.of("a", 1L)))).isFalse();
        assertThat(test(nested, k, new Predicate.Contains(Map.of("a", 1L, "b", 2L)))).isTrue();
        assertThat(test(nested, k, new Predicate.Contains(3L))).isTrue();
        assertThat(test(nested, k, new Predicate.Contains(3.0))).isTrue();
        FieldSpec lists = field("k", ScalarType.LIST);
        assertThat(test(nested, lists, new Predicate.Contains(List.of(1L)))).isFalse();
        assertThat(test(Map.of("k", List.of(List.of(List.of(1L)))), lists, new Predicate.Contains(List.of(List.of(1L)))))
                .isTrue();
        assertThat(test(Map.of(), lists, new Predicate.Contains(1L))).isFalse();
        FieldSpec noDefault = field("x", ScalarType.SET);
        assertThat(test(Map.of(), noDefault, new Predicate.Contains("a"))).isFalse();
        assertThat(test(Map.of(), noDefault, new Predicate.Not(new Predicate.Contains("a")))).isTrue();
    }

    /** R33: eq/ne on SET is order-independent set equality; LIST stays order-dependent. */
    @Test
    void setEqualityIgnoresOrder() {
        FieldSpec kits = field("kits", ScalarType.SET, List.of());
        Map<String, Object> data = Map.of("kits", List.of("a", "b"));
        assertThat(test(data, kits, new Predicate.Eq(List.of("b", "a")))).isTrue();
        assertThat(test(data, kits, new Predicate.Ne(List.of("b", "a")))).isFalse();
        assertThat(test(data, kits, new Predicate.Eq(List.of("a")))).isFalse();
        assertThat(test(data, kits, new Predicate.Eq(List.of("a", "b", "c")))).isFalse();
        assertThat(test(Map.of(), kits, new Predicate.Eq(List.of()))).isTrue();
        FieldSpec nested = field("n", ScalarType.SET);
        assertThat(test(Map.of("n", List.of(List.of(1L, 2L), 3L)), nested, new Predicate.Eq(List.of(3.0, List.of(1L, 2L)))))
                .isTrue();
        assertThat(test(Map.of("n", List.of(List.of(1L, 2L))), nested, new Predicate.Eq(List.of(List.of(2L, 1L)))))
                .isFalse();
        assertThat(test(data, kits, new Predicate.Ne(List.of("a", "c")))).isTrue();
        FieldSpec noDefault = field("k", ScalarType.SET);
        assertThat(test(Map.of(), noDefault, new Predicate.Eq(List.of()))).isFalse();
        assertThat(test(Map.of(), noDefault, new Predicate.Ne(List.of()))).isFalse();
        FieldSpec log = field("log", ScalarType.LIST, List.of());
        assertThat(test(Map.of("log", List.of("a", "b")), log, new Predicate.Eq(List.of("b", "a")))).isFalse();
        assertThat(test(Map.of("log", List.of("a", "b")), log, new Predicate.Eq(List.of("a", "b")))).isTrue();
    }

    /** R34: inc/withdraw on a missing field without default start at 0. */
    @Test
    void incStartsAtZeroWithoutDefault() {
        FieldSpec coins = field("coins", ScalarType.LONG);
        assertThat(OpSemantics.apply(Map.of(), new FieldOp.Inc(coins, 5))).isEqualTo(Map.of("coins", 5L));
        assertThat(OpSemantics.apply(Map.of(), new FieldOp.Withdraw(coins, 5))).isEqualTo(Map.of("coins", -5L));
        assertThat(OpSemantics.withdrawAllowed(Map.of(), new FieldOp.Withdraw(coins, 1))).isFalse();
        FieldSpec d = field("d", ScalarType.DOUBLE);
        assertThat(OpSemantics.apply(Map.of(), new FieldOp.Inc(d, -3))).isEqualTo(Map.of("d", -3.0));
        assertThat(OpSemantics.apply(Map.of(), new FieldOp.Inc(d, 2))).isEqualTo(Map.of("d", 2.0));
        assertThat(OpSemantics.apply(Map.of("d", 0.5), new FieldOp.Inc(d, 2))).isEqualTo(Map.of("d", 2.5));
    }

    /** R35: conditions are always true or false; every comparison with NULL is false. */
    @Test
    void whereNeverYieldsNull() {
        FieldSpec level = field("level", ScalarType.LONG);
        for (Predicate p : List.<Predicate>of(new Predicate.Eq(1L), new Predicate.Ne(1L), new Predicate.Gt(1L),
                new Predicate.Gte(1L), new Predicate.Lt(1L), new Predicate.Lte(1L), new Predicate.In(List.of(1L)))) {
            assertThat(test(Map.of(), level, p)).as(p.toString()).isFalse();
            assertThat(test(Map.of(), level, new Predicate.Not(p))).as("not " + p).isTrue();
        }
        assertThat(test(Map.of("level", 1L), level, new Predicate.Eq(null))).isFalse();
        assertThat(test(Map.of("level", 1L), level, new Predicate.Ne(null))).isFalse();
        assertThat(test(Map.of("level", 1L), level, new Predicate.Not(new Predicate.Eq(null)))).isTrue();
        assertThat(test(Map.of("level", 1L), level, new Predicate.In(Arrays.asList(null, 2L)))).isFalse();
        assertThat(test(Map.of("level", 2L), level, new Predicate.In(Arrays.asList(null, 2L)))).isTrue();
        assertThat(test(Map.of(), level, new Predicate.Not(new Predicate.Not(new Predicate.Eq(1L))))).isFalse();

        // Agora's condition table: on "{}" and on a filled row, p and not(p) are complementary (never "unknown").
        FieldSpec l = field("l", ScalarType.LONG);
        FieldSpec s = field("s", ScalarType.STRING);
        FieldSpec set = field("k", ScalarType.SET);
        FieldSpec obj = field("o", ScalarType.OBJECT);
        List<FieldCondition> conds = List.of(
                new FieldCondition(l, new Predicate.Eq(1L)),
                new FieldCondition(l, new Predicate.Ne(1L)),
                new FieldCondition(l, new Predicate.Gt(1L)),
                new FieldCondition(l, new Predicate.Lte(1L)),
                new FieldCondition(s, new Predicate.Lt("a")),
                new FieldCondition(l, new Predicate.In(Arrays.asList(1L, null))),
                new FieldCondition(set, new Predicate.Contains(1L)),
                new FieldCondition(set, new Predicate.Eq(List.of())),
                new FieldCondition(obj, new Predicate.Eq(Map.of("a", 1L))),
                new FieldCondition(l, new Predicate.Exists()));
        Map<String, Object> filled = Map.of("l", 2L, "s", "b", "k", List.of(2L), "o", Map.of());
        for (FieldCondition c : conds) {
            for (Map<String, Object> row : List.<Map<String, Object>>of(Map.of(), filled)) {
                boolean p = OpSemantics.test(row, c);
                boolean notP = OpSemantics.test(row, new FieldCondition(c.field(), new Predicate.Not(c.predicate())));
                assertThat(notP).as(c + " on " + row).isEqualTo(!p);
            }
            assertThat(OpSemantics.test(Map.of(), c)).as(c.toString()).isFalse();
        }
        assertThat(OpSemantics.test(Map.of("l", 2L), new FieldCondition(l, new Predicate.In(Arrays.asList(1L, null)))))
                .isFalse();
    }

    /** R36: overflow is an IllegalArgumentException; NaN/Infinity DOUBLE defaults are rejected. */
    @Test
    void overflowAndNonFiniteDefaultsAreRejected() {
        FieldSpec coins = field("coins", ScalarType.LONG, 0L);
        assertThatThrownBy(() -> OpSemantics.apply(Map.of("coins", Long.MAX_VALUE), new FieldOp.Inc(coins, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.apply(Map.of("coins", Long.MIN_VALUE), new FieldOp.Withdraw(coins, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            FieldSpec d = field("d", ScalarType.DOUBLE, bad);
            assertThatThrownBy(() -> OpSemantics.apply(Map.of(), new FieldOp.Inc(d, 1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OpSemantics.test(Map.of(), new FieldCondition(d, new Predicate.Gt(0L))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OpSemantics.order(List.of(new SortSpec(d, false))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void existsMeansKeyPresentAndNotNull() {
        FieldSpec home = field("home", ScalarType.STRING);
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("home", null);
        assertThat(test(withNull, home, new Predicate.Exists())).isFalse();
        assertThat(test(Map.of("home", "x"), home, new Predicate.Exists())).isTrue();
        assertThat(test(Map.of(), home, new Predicate.Not(new Predicate.Exists()))).isTrue();
    }

    @Test
    void missingCollectionWritesDefaultBase() {
        FieldSpec kits = field("kits", ScalarType.SET, List.of("a"));
        assertThat(OpSemantics.apply(Map.of(), new FieldOp.Add(kits, "a"))).isEqualTo(Map.of("kits", List.of("a")));
        assertThat(OpSemantics.apply(Map.of(), new FieldOp.Add(kits, "b"))).isEqualTo(Map.of("kits", List.of("a", "b")));
        assertThat(OpSemantics.apply(Map.of(), new FieldOp.Remove(kits, "a"))).isEqualTo(Map.of("kits", List.of()));
        FieldSpec level = field("level", ScalarType.LONG, 5L);
        assertThat(OpSemantics.apply(Map.of(), new FieldOp.Max(level, 3L))).isEqualTo(Map.of("level", 5L));
        FieldSpec noDefault = field("x", ScalarType.LIST);
        assertThat(OpSemantics.apply(Map.of(), new FieldOp.Add(noDefault, "a"))).isEqualTo(Map.of("x", List.of("a")));
    }

    @Test
    void typeMismatchesAreRejected() {
        FieldSpec name = field("name", ScalarType.STRING);
        FieldSpec obj = field("o", ScalarType.OBJECT);
        FieldSpec level = field("level", ScalarType.LONG);
        FieldSpec kits = field("kits", ScalarType.SET, List.of());
        assertThatThrownBy(() -> OpSemantics.apply(Map.of(), new FieldOp.Inc(name, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.apply(Map.of(), new FieldOp.Set(level, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.apply(Map.of(), new FieldOp.Set(level, 1.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.apply(Map.of(), new FieldOp.Add(kits, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.apply(Map.of(), new FieldOp.Add(level, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.apply(Map.of(), new FieldOp.Max(obj, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.apply(Map.of(), new FieldOp.Set(name, "a\u0000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.test(Map.of(), new FieldCondition(obj, new Predicate.Gt(Map.of()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.test(Map.of(), new FieldCondition(level, new Predicate.Contains(1L))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpSemantics.order(List.of(new SortSpec(kits, false))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doubleAndBooleanSort() {
        FieldSpec d = field("d", ScalarType.DOUBLE, 1.0);
        List<Map<String, Object>> rows = List.of(Map.of("d", 2L), Map.of(), Map.of("d", 1.5), Map.of("d", -0.5));
        assertThat(sortedIds(rows, List.of(new SortSpec(d, false)))).containsExactly(3, 1, 2, 0);
        FieldSpec b = field("b", ScalarType.BOOLEAN);
        FieldSpec n = field("n", ScalarType.LONG, 0L);
        List<Map<String, Object>> flags = List.of(Map.of("b", true, "n", 1L), Map.of("b", false, "n", 1L),
                Map.of("n", 9L), Map.of("b", true, "n", 2L));
        assertThat(sortedIds(flags, List.of(new SortSpec(b, false), new SortSpec(n, true)))).containsExactly(1, 3, 0, 2);
    }

    @Test
    void applyAllChainsInOrder() {
        FieldSpec coins = field("coins", ScalarType.LONG, 0L);
        FieldSpec kits = field("kits", ScalarType.SET, List.of());
        Map<String, Object> out = OpSemantics.applyAll(Map.of("coins", 10L), List.of(
                new FieldOp.Withdraw(coins, 4), new FieldOp.Add(kits, "a"), new FieldOp.Inc(coins, 1),
                new FieldOp.Add(kits, "b"), new FieldOp.Remove(kits, "a")));
        assertThat(out).isEqualTo(Map.of("coins", 7L, "kits", List.of("b")));
    }

    /** R37: in on SET is an OR of order-independent set equalities; LIST/OBJECT use ordered deep JSON equality. */
    @Test
    void inOnCollectionsUsesTypedEquality() {
        FieldSpec set = field("k", ScalarType.SET);
        Map<String, Object> ab = Map.of("k", List.of("a", "b"));
        assertThat(test(ab, set, new Predicate.In(List.of(List.of("x"), List.of("b", "a"))))).isTrue();
        assertThat(test(ab, set, new Predicate.In(List.of(List.of("a"), List.of("a", "b", "c"))))).isFalse();
        assertThat(test(Map.of(), set, new Predicate.In(List.of(List.of())))).isFalse();
        FieldSpec lst = field("k", ScalarType.LIST);
        assertThat(test(ab, lst, new Predicate.In(List.of(List.of("b", "a"))))).isFalse();
        assertThat(test(ab, lst, new Predicate.In(List.of(List.of("b", "a"), List.of("a", "b"))))).isTrue();
        FieldSpec obj = field("o", ScalarType.OBJECT);
        Map<String, Object> o = Map.of("o", Map.of("a", 1L, "b", List.of(1L, Map.of("c", "x"))));
        assertThat(test(o, obj, new Predicate.In(List.of(Map.of("b", List.of(1.0, Map.of("c", "x")), "a", 1.0)))))
                .isTrue();
        assertThat(test(o, obj, new Predicate.In(List.of(Map.of("a", 1L), Map.of("a", 1L, "b", List.of(1L))))))
                .isFalse();
    }

    @Test
    void descendingStringSortIsBinaryWithNullsLast() {
        FieldSpec name = field("name", ScalarType.STRING);
        List<Map<String, Object>> rows = List.of(Map.of("name", "B"), Map.of(), Map.of("name", "a"),
                Map.of("name", "\uD83D\uDE00"), Map.of("name", "\uFFFF"), Map.of());
        assertThat(sortedIds(rows, List.of(new SortSpec(name, true)))).containsExactly(3, 4, 2, 0, 1, 5);
        assertThat(sortedIds(rows, List.of(new SortSpec(name, false)))).containsExactly(0, 2, 4, 3, 1, 5);
    }

    /** R38: a missing field without default counts as 0 for withdraw, also after a preceding inc. */
    @Test
    void withdrawAfterIncOnMissingFieldWithoutDefault() {
        FieldSpec coins = field("coins", ScalarType.LONG);
        assertThat(apply(Map.of(), new FieldOp.Inc(coins, 5), new FieldOp.Withdraw(coins, 3)))
                .isEqualTo(Map.of("coins", 2L));
        assertThat(apply(Map.of(), new FieldOp.Inc(coins, 2), new FieldOp.Withdraw(coins, 3))).isNull();
    }

    // ---------- harness ----------

    /**
     * Applies the ops to {@code initial} like Agora's {@code UPDATE … WHERE <implicit conditions>}: each withdraw is
     * checked against the state after the preceding ops; returns {@code null} if a withdraw is not allowed.
     */
    private static @Nullable Map<String, Object> apply(Map<String, Object> initial, FieldOp... ops) {
        Map<String, Object> current = initial;
        for (FieldOp op : ops) {
            if (op instanceof FieldOp.Withdraw w && !OpSemantics.withdrawAllowed(current, w)) return null;
            current = OpSemantics.apply(current, op);
        }
        return current;
    }

    /** The field's value with default, as a condition/sort sees it. */
    private static @Nullable Object read(@Nullable Map<String, Object> data, FieldSpec f) {
        assertThat(data).isNotNull();
        return OpSemantics.read(data, f);
    }

    private static boolean test(Map<String, Object> data, FieldSpec f, Predicate p) {
        return OpSemantics.test(data, new FieldCondition(f, p));
    }

    /** Sorts the rows (id = list index) with {@link OpSemantics#order} plus an ascending id tie-break. */
    private static List<Integer> sortedIds(List<Map<String, Object>> rows, List<SortSpec> sorts) {
        var order = OpSemantics.order(sorts);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) ids.add(i);
        ids.sort((a, b) -> {
            int c = order.compare(rows.get(a), rows.get(b));
            return c != 0 ? c : Integer.compare(a, b);
        });
        return ids;
    }

    private static FieldSpec field(String name, ScalarType type) {
        return new FieldSpec("t", name, type, null);
    }

    private static FieldSpec field(String name, ScalarType type, Object def) {
        return new FieldSpec("t", name, type, def);
    }
}
