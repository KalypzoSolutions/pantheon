package de.kalypzo.pantheon.core.backend.agora;

import static de.kalypzo.pantheon.core.backend.agora.Bench.COINS;
import static de.kalypzo.pantheon.core.backend.agora.Bench.get;
import static de.kalypzo.pantheon.core.backend.agora.Bench.inc;
import static de.kalypzo.pantheon.core.backend.agora.Bench.write;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * S5: a write on node A until node B sees it in {@code onDocChanged}: a player B holds ({@code player.data()} on the
 * other server) and a store B watches ({@code cached()}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class PropagationBench {

    private AgoraContractFixture fx;
    private Backend a;
    private final UUID player = UUID.randomUUID();
    private final StoreDoc storeDoc = new StoreDoc(new StoreRef("bench", "propagation"), "d");
    private volatile Object expectedRef;
    private volatile long expected; // one counter is enough: JMH runs each benchmark method in its own fork
    private volatile CompletableFuture<Void> seen = new CompletableFuture<>();

    @Setup(Level.Trial)
    public void setUp() {
        fx = new AgoraContractFixture(16);
        a = fx.node("lobby-01");
        Backend b = fx.node("lobby-02");
        b.onDocChanged(this::changed);
        get(b.loadPlayer(player));
        get(a.create(Bench.key(), storeDoc, Map.of(COINS.name(), 0L)));
        b.watchStore(storeDoc.store());
        get(b.streamBarrier());
    }

    private void changed(DocChanged e) {
        if (e.ref().equals(expectedRef) && !e.deleted() && Bench.coins(e.data()) >= expected) seen.complete(null);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        fx.close();
    }

    @Benchmark
    public void playerToHolder() {
        roundTrip(new PlayerRow(player, "bench"), new PlayerDoc(player));
    }

    @Benchmark
    public void storeToWatcher() {
        roundTrip(storeDoc, storeDoc);
    }

    private void roundTrip(Object ref, de.kalypzo.pantheon.core.data.wire.DocRef doc) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        expectedRef = ref;
        expected = expected + 1;
        seen = f;
        a.apply(write(doc, inc(COINS, 1)));
        get(f);
    }
}
