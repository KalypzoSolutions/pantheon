package de.kalypzo.pantheon.core.data.wire;

import java.util.Map;
import org.jspecify.annotations.Nullable;

public record DocChanged(RowRef ref, long version, boolean deleted, Map<String, Object> data,
                         @Nullable String idempotencyKey) {
    public DocChanged {
        data = Wire.immutableMap(data);
    }
}
