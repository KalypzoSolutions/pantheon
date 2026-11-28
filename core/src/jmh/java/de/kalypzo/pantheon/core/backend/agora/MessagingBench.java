package de.kalypzo.pantheon.core.backend.agora;

import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.Delivery;
import de.kalypzo.pantheon.core.backend.DeliveryKind;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.world.NetworkLocation;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
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
 * S7: a chat message from node A until the player's proxy has it ({@code player.sendMessage} to someone on another
 * server), and {@code location(uuid)} from node B, answered by node A where the player is.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class MessagingBench {

    private static final Delivery CHAT = new Delivery(DeliveryKind.CHAT, Component.text("hello"), null, null, null);

    private AgoraContractFixture fx;
    private Backend a;
    private Backend b;
    private final UUID player = UUID.randomUUID();
    private volatile CompletableFuture<Void> arrived = new CompletableFuture<>();

    @Setup(Level.Trial)
    public void setUp() {
        fx = new AgoraContractFixture(16);
        a = fx.node("lobby-01");
        b = fx.node("lobby-02");
        fx.onDelivered(d -> arrived.complete(null));
        NetworkLocation where = new NetworkLocation("lobby-01", "world", 1.5, 64, -3, 90, 10);
        a.answerLocations(p -> CompletableFuture.completedFuture(Optional.of(where)));
        fx.join(new PresenceEntry(player, "Bench", null, AgoraContractFixture.PROXY, Locale.GERMANY, Instant.now()));
        fx.switchServer(player, "lobby-01");
        await().atMost(Duration.ofSeconds(20)).ignoreExceptions()
                .until(() -> b.location(player).get(5, TimeUnit.SECONDS) != null);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        fx.close();
    }

    @Benchmark
    public void deliverToProxy() {
        CompletableFuture<Void> f = new CompletableFuture<>();
        arrived = f;
        a.deliver(player, CHAT);
        Bench.get(f);
    }

    @Benchmark
    public NetworkLocation location() {
        return Bench.get(b.location(player));
    }
}
