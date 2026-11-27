package de.kalypzo.pantheon.core.data.wire;

/** A store: namespace plus name. */
public record StoreRef(String namespace, String name) {
    /** For example {@code lobby:clans}. */
    public String qualified() {
        return namespace + ":" + name;
    }
}
