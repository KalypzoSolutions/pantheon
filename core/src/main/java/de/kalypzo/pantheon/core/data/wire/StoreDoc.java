package de.kalypzo.pantheon.core.data.wire;

/** A document of a store. */
public record StoreDoc(StoreRef store, String id) implements DocRef, RowRef {}
