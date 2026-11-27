package de.kalypzo.pantheon.core.outbox;

import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import java.time.Instant;

/** One persisted unconditional request, never changed after it was written (agora.md §8). */
public record OutboxEntry(long seq, String idempotencyKey, String docKey, WriteRequest request, Instant createdAt) {}
