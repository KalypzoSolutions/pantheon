package de.kalypzo.pantheon.core.data.wire;

public sealed interface QueryTarget permits QueryTarget.Players, QueryTarget.StoreTarget {

    record Players() implements QueryTarget {}

    record StoreTarget(StoreRef store) implements QueryTarget {}
}
