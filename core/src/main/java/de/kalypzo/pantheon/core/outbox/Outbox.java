package de.kalypzo.pantheon.core.outbox;

import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The local outbox for unconditional requests that were not confirmed (agora.md §8, E13). {@link #hasEntries} is
 * O(1) and takes no lock: the writer calls it under its lock before every send.
 *
 * <p>Drainer contract: after a successful replay the drainer calls {@link #remove} <b>before</b>
 * {@code DocWriter.outboxAcked}. The writer checks {@link #hasEntries} in {@code outboxAcked} to release requests
 * waiting behind the outbox; with the entry still present they would wait until they time out.
 */
public interface Outbox extends AutoCloseable {

    /**
     * Opens (and creates) the SQLite outbox at {@code file}, e.g. {@code plugins/Pantheon/outbox.db}. {@code clock}
     * decides the age in {@link #moveExpired}.
     *
     * @throws IllegalStateException if the file cannot be opened
     */
    static Outbox open(Path file, Clock clock, Logger log) {
        return SqliteOutbox.open(file, clock, log);
    }

    /** Appends a request under its idempotency key; conditional requests are rejected with an IAE. */
    void append(String docKey, WriteRequest request);

    boolean hasEntries(String docKey);

    /** Per document, in {@code seq} order. */
    Map<String, List<OutboxEntry>> pendingByDoc();

    /** Entries of one document in {@code seq} order; empty if none. */
    default List<OutboxEntry> pending(String docKey) {
        return pendingByDoc().getOrDefault(docKey, List.of());
    }

    /** True while the entry is in the outbox (not removed, not moved to {@code outbox_dead}). */
    default boolean contains(String idempotencyKey) {
        for (List<OutboxEntry> entries : pendingByDoc().values()) {
            for (OutboxEntry e : entries) {
                if (e.idempotencyKey().equals(idempotencyKey)) return true;
            }
        }
        return false;
    }

    /** Removes the entry; afterwards it no longer counts for {@link #hasEntries}, even if the file write failed. */
    void remove(String idempotencyKey);

    /**
     * Moves entries older than {@code maxAge} to {@code outbox_dead} and logs each as an ERROR with document and ops as
     * JSON; returns how many.
     */
    default int moveExpired(Duration maxAge) {
        return moveExpired(maxAge, entry -> {});
    }

    /**
     * Like {@link #moveExpired(Duration)}; {@code onDead} gets every moved entry after it has left the outbox, outside
     * the outbox's lock (the drainer reports it to the writer as rejected).
     */
    int moveExpired(Duration maxAge, Consumer<OutboxEntry> onDead);

    @Override
    void close();

    /** {@code player:<uuid>} or {@code <namespace>:<store>/<id>}. */
    static String docKey(DocRef doc) {
        return switch (doc) {
            case PlayerDoc p -> "player:" + p.player();
            case StoreDoc s -> s.store().qualified() + "/" + s.id();
        };
    }
}
