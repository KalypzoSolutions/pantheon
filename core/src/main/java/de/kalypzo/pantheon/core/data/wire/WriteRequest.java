package de.kalypzo.pantheon.core.data.wire;

import java.time.Instant;
import java.util.List;

public record WriteRequest(String idempotencyKey, DocRef doc, List<FieldOp> ops, List<FieldCondition> conditions,
                           Instant createdAt) {
    public WriteRequest {
        ops = List.copyOf(ops);
        conditions = List.copyOf(conditions);
    }

    /** True if conditions are present or an op is a {@link FieldOp.Withdraw}. */
    public boolean conditional() {
        return !conditions.isEmpty() || ops.stream().anyMatch(FieldOp.Withdraw.class::isInstance);
    }
}
