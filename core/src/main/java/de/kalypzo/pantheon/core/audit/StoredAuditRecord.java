package de.kalypzo.pantheon.core.audit;

/** An entry as stored, with the node that wrote it. */
public record StoredAuditRecord(AuditRecord record, String server) {}
