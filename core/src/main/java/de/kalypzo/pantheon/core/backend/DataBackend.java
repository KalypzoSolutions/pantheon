package de.kalypzo.pantheon.core.backend;

import de.kalypzo.pantheon.core.data.wire.ApplyResult;
import de.kalypzo.pantheon.core.data.wire.CreateResult;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.DocSnapshot;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.IndexDeclaration;
import de.kalypzo.pantheon.core.data.wire.QuerySpec;
import de.kalypzo.pantheon.core.data.wire.QueryTarget;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.RowRef;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.data.wire.TransferResult;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.data.Subscription;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Data port (agora.md §2, §5). Failure contract, identical for every implementation: not connected or deadline
 * exceeded → the future fails with {@code DataUnavailableException}; a {@code unique} violation →
 * {@code DuplicateValueException}; invalid input → {@link IllegalArgumentException}.
 */
public interface DataBackend {

    /** Registers this node as holder first, then reads all rows of the player. */
    CompletableFuture<List<Row>> loadPlayer(UUID player);

    /** Fire-and-forget: this node no longer holds the player. */
    void releasePlayer(UUID player);

    /** Empty = not present. Does not make this node a holder. */
    CompletableFuture<List<Row>> load(DocRef doc);

    CompletableFuture<List<DocSnapshot>> query(QueryTarget target, QuerySpec spec);

    CompletableFuture<Long> count(QueryTarget target, List<FieldCondition> where);

    /** One version per requested row (missing: 0), plus every row of each given player. */
    CompletableFuture<Map<RowRef, Long>> versions(Collection<RowRef> rows, Collection<UUID> players);

    CompletableFuture<ApplyResult> apply(WriteRequest request);

    CompletableFuture<CreateResult> create(String idempotencyKey, StoreDoc doc, Map<String, Object> data);

    CompletableFuture<Boolean> delete(String idempotencyKey, StoreDoc doc);

    CompletableFuture<Void> expire(String idempotencyKey, StoreDoc doc, Instant at);

    CompletableFuture<TransferResult> transfer(String idempotencyKey, DocRef from, DocRef to, FieldSpec field,
                                               long amount);

    CompletableFuture<Void> declareIndex(IndexDeclaration index);

    void watchStore(StoreRef store);

    void unwatchStore(StoreRef store);

    /**
     * Completes once the backend has processed every fire-and-forget message ({@link #releasePlayer},
     * {@link #watchStore}, {@link #unwatchStore}) this node sent before the call (R45). A {@link #loadPlayer} right
     * after a {@link #releasePlayer} of the same player waits for it, so the late release cannot remove the fresh
     * holder. Implementations whose messages take effect at once (in memory) keep the default.
     */
    default CompletableFuture<Void> streamBarrier() {
        return CompletableFuture.completedFuture(null);
    }

    /** Changes of documents this node holds, in stream order, on a backend thread. */
    Subscription onDocChanged(Consumer<DocChanged> listener);
}
