package de.kalypzo.pantheon.paper;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.player.PanPlayer;
import de.kalypzo.pantheon.world.NetworkLocation;
import de.kalypzo.pantheon.world.TeleportResult;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The local path of {@code PanPlayer#teleport} and {@code #connect} (network-teleport T-8). */
class LocalTeleportTest {

    private ServerMock server;
    private PlayerMock player;
    private LocalPanPlayerImpl local;
    private PanPlayer network;
    private Doc data;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        player = new PaperPlayerMock(server, "Notch");
        server.addPlayer(player);
        // the network view is a fake: every network call answers TIMEOUT
        network = (PanPlayer) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PanPlayer.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "teleport", "connect" -> CompletableFuture.completedFuture(TeleportResult.TIMEOUT);
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        data = (Doc) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Doc.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException();
                });
        local = new LocalPanPlayerImpl(player, data, Runnable::run, "lobby-01", self -> network);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private TeleportResult teleport(String node, String world, double x) {
        return local.teleport(new NetworkLocation(node, world, x, 70, -3, 90, 10)).join();
    }

    @Test
    void teleportsOnThisServer() {
        assertThat(teleport("lobby-01", "world", 5)).isEqualTo(TeleportResult.OK);
        assertThat(player.getLocation().getX()).isEqualTo(5);
        assertThat(player.getLocation().getY()).isEqualTo(70);
        assertThat(player.getLocation().getWorld().getName()).isEqualTo("world");
    }

    @Test
    void unknownWorld() {
        assertThat(teleport("lobby-01", "nope", 5)).isEqualTo(TeleportResult.WORLD_NOT_FOUND);
    }

    @Test
    void nanCoordinatesAreConnectFailed() {
        assertThat(teleport("lobby-01", "world", Double.NaN)).isEqualTo(TeleportResult.CONNECT_FAILED);
    }

    @Test
    void rejectedMainThreadIsConnectFailed() {
        LocalPanPlayerImpl rejecting = new LocalPanPlayerImpl(player, data, r -> {
            throw new java.util.concurrent.RejectedExecutionException();
        }, "lobby-01", self -> network);
        assertThat(rejecting.teleport(new NetworkLocation("lobby-01", "world", 1, 2, 3, 0, 0)).join())
                .isEqualTo(TeleportResult.CONNECT_FAILED);
    }

    @Test
    void otherServerDelegatesToNetwork() {
        assertThat(teleport("lobby-02", "world", 5)).isEqualTo(TeleportResult.TIMEOUT);
        assertThat(local.connect("lobby-02").join()).isEqualTo(TeleportResult.TIMEOUT);
        assertThat(local.connect("lobby-01").join()).isEqualTo(TeleportResult.OK);
    }
}
