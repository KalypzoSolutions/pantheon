package de.kalypzo.pantheon.audit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Audit area of a plugin, e.g. {@code shop}. Independent of the Data API's namespace. Interned by name. */
public final class AuditNamespace {

    static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    private static final Map<String, AuditNamespace> INTERNED = new ConcurrentHashMap<>();

    private final String name;

    private AuditNamespace(String name) {
        this.name = name;
    }

    /** @throws IllegalArgumentException if the name does not match {@code [a-z0-9_]+} */
    public static AuditNamespace of(String name) {
        check(name, "audit namespace");
        return INTERNED.computeIfAbsent(name, AuditNamespace::new);
    }

    static void check(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid " + what + " name '" + name + "', must match [a-z0-9_]+");
        }
    }

    public String name() {
        return name;
    }

    /** @throws IllegalArgumentException if the name does not match {@code [a-z0-9_]+} */
    public AuditAction action(String name) {
        check(name, "audit action");
        return new AuditAction(this, name);
    }

    @Override
    public String toString() {
        return name;
    }
}
