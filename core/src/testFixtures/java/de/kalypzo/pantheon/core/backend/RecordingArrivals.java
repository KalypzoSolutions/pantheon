package de.kalypzo.pantheon.core.backend;

import de.kalypzo.pantheon.world.NetworkLocation;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** An {@link ArrivalHandler} that records every call and answers with {@link #answer} (default: {@code READY}). */
public final class RecordingArrivals implements ArrivalHandler {

    public record Prepared(String requestId, UUID player, @Nullable NetworkLocation location) {}

    public final BlockingQueue<Prepared> prepared = new LinkedBlockingQueue<>();
    public final BlockingQueue<String> cancelled = new LinkedBlockingQueue<>();
    public volatile Function<Prepared, CompletableFuture<ArrivalStatus>> answer =
            p -> CompletableFuture.completedFuture(ArrivalStatus.READY);
    /** The latest prepare, not consumed by {@link #nextPrepared()}. */
    public volatile @Nullable Prepared last;

    @Override
    public CompletableFuture<ArrivalStatus> prepare(String requestId, UUID player, @Nullable NetworkLocation location) {
        Prepared p = new Prepared(requestId, player, location);
        last = p;
        prepared.add(p);
        return answer.apply(p);
    }

    @Override
    public void cancel(String requestId, UUID player) {
        cancelled.add(requestId);
    }

    /** The next recorded prepare; waits at most 10 s. */
    public Prepared nextPrepared() throws InterruptedException {
        Prepared p = prepared.poll(10, TimeUnit.SECONDS);
        if (p == null) throw new AssertionError("no PrepareArrival within 10 s");
        return p;
    }
}
