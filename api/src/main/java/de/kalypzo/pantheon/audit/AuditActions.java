package de.kalypzo.pantheon.audit;

/** Actions Pantheon brings itself, in the audit namespace {@code pantheon}. */
public final class AuditActions {

    public static final AuditNamespace PANTHEON = AuditNamespace.of("pantheon");

    public static final AuditAction CHAT = PANTHEON.action("chat");
    public static final AuditAction COMMAND = PANTHEON.action("command");
    public static final AuditAction JOIN = PANTHEON.action("join");
    public static final AuditAction QUIT = PANTHEON.action("quit");
    public static final AuditAction SWITCH = PANTHEON.action("switch");
    public static final AuditAction PRIVATE_MESSAGE = PANTHEON.action("private_message");
    public static final AuditAction AUDIT_VIEW = PANTHEON.action("audit_view");

    private AuditActions() {}
}
