package de.kalypzo.pantheon.audit;

/** An action in an {@link AuditNamespace}, e.g. {@code shop:buy}. Equal by {@link #key()}. */
public final class AuditAction {

    private final AuditNamespace namespace;
    private final String name;

    AuditAction(AuditNamespace namespace, String name) {
        this.namespace = namespace;
        this.name = name;
    }

    public AuditNamespace namespace() {
        return namespace;
    }

    public String name() {
        return name;
    }

    /** {@code <namespace>:<name>}. */
    public String key() {
        return namespace.name() + ":" + name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AuditAction other && key().equals(other.key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }

    @Override
    public String toString() {
        return key();
    }
}
