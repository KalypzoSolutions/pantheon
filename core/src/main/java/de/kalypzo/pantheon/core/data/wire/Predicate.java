package de.kalypzo.pantheon.core.data.wire;

import java.util.List;
import org.jspecify.annotations.Nullable;

public sealed interface Predicate {

    record Eq(@Nullable Object value) implements Predicate {
        public Eq {
            value = Wire.immutable(value);
        }
    }

    record Ne(@Nullable Object value) implements Predicate {
        public Ne {
            value = Wire.immutable(value);
        }
    }

    record Gt(Object value) implements Predicate {
        public Gt {
            value = Wire.immutable(value);
        }
    }

    record Gte(Object value) implements Predicate {
        public Gte {
            value = Wire.immutable(value);
        }
    }

    record Lt(Object value) implements Predicate {
        public Lt {
            value = Wire.immutable(value);
        }
    }

    record Lte(Object value) implements Predicate {
        public Lte {
            value = Wire.immutable(value);
        }
    }

    record Contains(Object value) implements Predicate {
        public Contains {
            value = Wire.immutable(value);
        }
    }

    @SuppressWarnings("unchecked")
    record In(List<Object> values) implements Predicate {
        public In {
            values = (List<Object>) Wire.immutable(values);
        }
    }

    record Exists() implements Predicate {}

    record Not(Predicate predicate) implements Predicate {}
}
