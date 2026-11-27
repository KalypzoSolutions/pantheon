package de.kalypzo.pantheon.core.data.wire;

import org.jspecify.annotations.Nullable;

public sealed interface FieldOp {

    FieldSpec field();

    record Set(FieldSpec field, @Nullable Object value) implements FieldOp {
        public Set {
            value = Wire.immutable(value);
        }
    }

    record Unset(FieldSpec field) implements FieldOp {}

    record Inc(FieldSpec field, long delta) implements FieldOp {}

    record Max(FieldSpec field, Object value) implements FieldOp {
        public Max {
            value = Wire.immutable(value);
        }
    }

    record Min(FieldSpec field, Object value) implements FieldOp {
        public Min {
            value = Wire.immutable(value);
        }
    }

    record Add(FieldSpec field, @Nullable Object element) implements FieldOp {
        public Add {
            element = Wire.immutable(element);
        }
    }

    record Remove(FieldSpec field, @Nullable Object element) implements FieldOp {
        public Remove {
            element = Wire.immutable(element);
        }
    }

    record Withdraw(FieldSpec field, long amount) implements FieldOp {}
}
