package de.kalypzo.pantheon.core.data.wire;

import java.util.List;

public record DocSnapshot(DocRef doc, List<Row> rows) {
    public DocSnapshot {
        rows = List.copyOf(rows);
    }
}
