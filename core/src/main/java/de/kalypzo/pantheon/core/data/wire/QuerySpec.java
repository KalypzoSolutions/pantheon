package de.kalypzo.pantheon.core.data.wire;

import java.util.List;

public record QuerySpec(List<FieldCondition> where, List<SortSpec> sort, int limit, int offset) {
    public QuerySpec {
        where = List.copyOf(where);
        sort = List.copyOf(sort);
    }
}
