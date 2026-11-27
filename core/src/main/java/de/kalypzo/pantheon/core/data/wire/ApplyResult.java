package de.kalypzo.pantheon.core.data.wire;

import java.util.List;

public record ApplyResult(boolean applied, List<Row> rows) {
    public ApplyResult {
        rows = List.copyOf(rows);
    }
}
