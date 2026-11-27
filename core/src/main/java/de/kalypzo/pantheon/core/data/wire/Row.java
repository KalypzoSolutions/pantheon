package de.kalypzo.pantheon.core.data.wire;

import java.util.Map;

public record Row(RowRef ref, long version, Map<String, Object> data) {
    public Row {
        data = Wire.immutableMap(data);
    }
}
