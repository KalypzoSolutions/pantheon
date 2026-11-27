package de.kalypzo.pantheon.core.data.wire;

import java.util.List;

public record TransferResult(boolean transferred, List<Row> rows) {
    public TransferResult {
        rows = List.copyOf(rows);
    }
}
