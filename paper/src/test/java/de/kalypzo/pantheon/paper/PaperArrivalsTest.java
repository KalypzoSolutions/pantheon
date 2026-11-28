package de.kalypzo.pantheon.paper;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.core.backend.ArrivalStatus;
import de.kalypzo.pantheon.world.NetworkLocation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** Arrivals of network teleports on Paper (network-teleport T-9). */
class PaperArrivalsTest {

    private static final UUID UUID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private ServerMock server;
    private World world;
    private final ArrayDeque<Runnable> main = new ArrayDeque<>();
    private final List<Map.Entry<String, Boolean>> reports = new ArrayList<>();
    private Instant now = Instant.parse("2026-11-28T12:00:00Z");
    private boolean joined = true;
    private PaperArrivals arrivals;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        arrivals = new PaperArrivals(server, uuid -> joined, (id, ok) -> reports.add(Map.entry(id, ok)), main::add,
                clock, Logger.getLogger("PaperArrivalsTest"));
        server.getPluginManager().registerEvents(arrivals, MockBukkit.createMockPlugin());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void step() {
        main.remove().run();
    }

    private void runAll() {
        while (!main.isEmpty()) step();
    }

    private static NetworkLocation at(String world, double y) {
        return new NetworkLocation("lobby-01", world, 1.5, y, -3, 90, 10);
    }

    private ArrivalStatus prepare(@org.jspecify.annotations.Nullable NetworkLocation location) {
        CompletableFuture<ArrivalStatus> f = arrivals.prepare("r1", UUID_1, location);
        runAll();
        return f.join();
    }

    private PaperPlayerMock join() {
        PaperPlayerMock p = new PaperPlayerMock(server, "Notch", UUID_1);
        server.addPlayer(p); // fires PlayerJoinEvent
        runAll();
        return p;
    }

    @Test
    void readyAndSpawnSetsLocation() {
        assertThat(prepare(at("world", 64))).isEqualTo(ArrivalStatus.READY);
        assertThat(arrivals.spawnLocation(UUID_1)).contains(new Location(world, 1.5, 64, -3, 90, 10));
        join();
        assertThat(reports).containsExactly(Map.entry("r1", true));
        assertThat(arrivals.spawnLocation(UUID_1)).isEmpty();
    }

    @Test
    void unknownWorldIsWorldNotFound() {
        assertThat(prepare(at("nope", 64))).isEqualTo(ArrivalStatus.WORLD_NOT_FOUND);
        assertThat(arrivals.spawnLocation(UUID_1)).isEmpty();
    }

    @Test
    void alreadyHereWithUnknownWorldIsWorldNotFound() {
        join();
        assertThat(prepare(at("nope", 64))).isEqualTo(ArrivalStatus.WORLD_NOT_FOUND);
        assertThat(reports).isEmpty();
    }

    @Test
    void alreadyHereAnswersBeforeTeleport() {
        PaperPlayerMock p = join();
        CompletableFuture<ArrivalStatus> f = arrivals.prepare("r1", UUID_1, at("world", 80));
        step();
        assertThat(f).isCompletedWithValue(ArrivalStatus.ALREADY_HERE);
        assertThat(p.getLocation().getY()).isNotEqualTo(80);
        assertThat(reports).isEmpty();
        runAll();
        assertThat(p.getLocation()).isEqualTo(new Location(world, 1.5, 80, -3, 90, 10));
        assertThat(reports).containsExactly(Map.entry("r1", true));
    }

    @Test
    void connectAlreadyHereReportsAtOnce() {
        join();
        assertThat(prepare(null)).isEqualTo(ArrivalStatus.ALREADY_HERE);
        assertThat(reports).containsExactly(Map.entry("r1", true));
    }

    @Test
    void notAppliedAtSpawnTeleportsAfterJoin() {
        assertThat(prepare(at("world", 80))).isEqualTo(ArrivalStatus.READY);
        PaperPlayerMock p = join();
        assertThat(p.getLocation()).isEqualTo(new Location(world, 1.5, 80, -3, 90, 10));
        assertThat(reports).containsExactly(Map.entry("r1", true));
    }

    @Test
    void fallbackWithNanReportsFailure() {
        assertThat(prepare(at("world", Double.NaN))).isEqualTo(ArrivalStatus.READY);
        join();
        assertThat(reports).containsExactly(Map.entry("r1", false));
    }

    @Test
    void cancelWithForeignIdKeepsEntry() {
        prepare(at("world", 64));
        arrivals.cancel("other", UUID_1);
        assertThat(arrivals.spawnLocation(UUID_1)).isNotEmpty();
    }

    @Test
    void cancelWithOwnIdRemoves() {
        prepare(at("world", 64));
        arrivals.cancel("r1", UUID_1);
        assertThat(arrivals.spawnLocation(UUID_1)).isEmpty();
        join();
        assertThat(reports).isEmpty();
    }

    @Test
    void expiredEntryIsIgnored() {
        prepare(at("world", 64));
        now = now.plus(Duration.ofSeconds(36));
        assertThat(arrivals.spawnLocation(UUID_1)).isEmpty();
        join();
        assertThat(reports).isEmpty();
    }

    @Test
    void joinWithoutHandleReportsFailure() {
        prepare(at("world", 64));
        joined = false;
        join();
        assertThat(reports).containsExactly(Map.entry("r1", false));
    }

    @Test
    void connectEntryReportsOnJoin() {
        assertThat(prepare(null)).isEqualTo(ArrivalStatus.READY);
        join(); // no location: nothing to teleport to
        assertThat(reports).containsExactly(Map.entry("r1", true));
    }
}
