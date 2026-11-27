package de.kalypzo.pantheon.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.runtime.BootstrapResult;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.data.PlayerFields;
import de.kalypzo.pantheon.player.ExpectedOnlinePlayerException;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.PanPlayerLoadEvent;
import de.kalypzo.pantheon.player.SessionData;
import de.kalypzo.pantheon.spi.PantheonProvider;
import de.kalypzo.pantheon.world.NetworkLocation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Login, join and quit on Paper in memory mode (P-24; player.md §7, §9, E25, R12). */
class PlayerLifecycleListenerTest {

    private static final DataNamespace LOBBY = DataNamespace.of("lobby");
    private static final Field<Long> COINS = LOBBY.ofLong("coins", 0L);
    private static final SessionData<String> KIT = SessionData.of("kit");

    private ServerMock server;
    private PantheonPlugin pantheon;
    private JavaPlugin other;
    private final List<PanPlayerLoadEvent> loads = new CopyOnWriteArrayList<>();
    private final List<Component> preLoginKicks = new CopyOnWriteArrayList<>();
    private volatile boolean refuseLogin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PantheonPlugin.environment = () -> Map.of("PANTHEON_BACKEND", "memory", "PANTHEON_NODE_ID", "lobby-1");
        pantheon = MockBukkit.load(PantheonPlugin.class);
        other = MockBukkit.createMockPlugin("Other");
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onLoad(PanPlayerLoadEvent event) {
                loads.add(event);
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onPreLogin(AsyncPlayerPreLoginEvent event) {
                if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                    preLoginKicks.add(event.kickMessage());
                }
            }

            @EventHandler(priority = EventPriority.HIGH)
            @SuppressWarnings("deprecation")
            public void onLogin(PlayerLoginEvent event) {
                if (refuseLogin) event.disallow(PlayerLoginEvent.Result.KICK_FULL, Component.text("full"));
            }
        }, other);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        PantheonPlugin.environment = System::getenv;
        PantheonProvider.unregister();
    }

    @Test
    void joinCreatesLocalPlayerWithLoadedData() {
        PlayerMock player = server.addPlayer("Notch");

        LocalPanPlayer local = runtime().localPlayers().get(player.getUniqueId()).orElseThrow();
        assertThat(local.bukkit()).isSameAs(player);
        assertThat(local.data().get(PlayerFields.NAME)).isEqualTo("Notch");
        assertThat(runtime().panPlayers().local(player)).isSameAs(local);
        assertThat(loads).singleElement().satisfies(e -> {
            assertThat(e.uniqueId()).isEqualTo(player.getUniqueId());
            assertThat(e.data().get(PlayerFields.NAME)).isEqualTo("Notch");
        });
        // R12: memory mode reports the player at this node
        assertThat(network().node("reader").snapshot()).extracting(PresenceEntry::player, PresenceEntry::server)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(player.getUniqueId(), "lobby-1"));
    }

    @Test
    void sessionDataIsPerHandleAndNullRemoves() {
        PlayerMock player = server.addPlayer("Notch");
        LocalPanPlayer local = runtime().localPlayers().get(player.getUniqueId()).orElseThrow();

        local.session(KIT, "warrior");
        assertThat(local.session(KIT)).contains("warrior");
        local.session(KIT, null);
        assertThat(local.session(KIT)).isEmpty();
    }

    @Test
    void quitEndsTheHandleAndReleases() throws Exception {
        PlayerMock player = server.addPlayer("Notch");
        UUID uuid = player.getUniqueId();
        LocalPanPlayer local = runtime().localPlayers().get(uuid).orElseThrow();
        local.session(KIT, "warrior");
        local.data().inc(COINS, 5L); // a change until the quit is saved

        player.disconnect();

        assertThat(runtime().localPlayers().get(uuid)).isEmpty();
        assertThat(started().players().heldPlayers()).doesNotContain(uuid);
        assertThat(started().players().profile(uuid)).isEmpty();
        assertThat(network().node("reader").snapshot()).isEmpty();
        for (Runnable call : List.<Runnable>of(local::bukkit, local::data, () -> local.session(KIT),
                () -> local.session(KIT, "x"), () -> local.hasPermission("a.b"))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ExpectedOnlinePlayerException.class, e -> {
                assertThat(e.uuid()).isEqualTo(uuid);
                assertThat(e.scope()).isEqualTo(ExpectedOnlinePlayerException.Scope.LOCAL);
            });
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> started().players().loadSnapshot(uuid)
                .get(5, TimeUnit.SECONDS).map(d -> d.get(COINS)).orElse(0L) == 5L);
    }

    @Test
    void loginWithoutDataIsRefusedWithDataUnavailable() {
        network().outage(); // Agora not reachable

        PlayerMock player = server.addPlayer("Notch");

        assertThat(runtime().localPlayers().get(player.getUniqueId())).isEmpty();
        assertThat(loads).isEmpty();
        assertThat(preLoginKicks).singleElement().satisfies(c ->
                assertThat(PlainTextComponentSerializer.plainText().serialize(c)).contains("Spielerdaten"));
        assertThat(started().players().heldPlayers()).isEmpty();
    }

    @Test
    void loginRefusedAfterPreLoginReleases() {
        refuseLogin = true; // another plugin refuses the PlayerLoginEvent

        PlayerMock player = server.addPlayer("Notch");

        assertThat(loads).hasSize(1); // the pre-login had loaded the data
        assertThat(runtime().localPlayers().get(player.getUniqueId())).isEmpty();
        assertThat(started().players().heldPlayers()).doesNotContain(player.getUniqueId());
    }

    @Test
    void locationOfLocalPlayerIsAnsweredOnTheMainThread() throws Exception {
        PlayerMock player = server.addPlayer("Notch");

        var future = network().node("proxy").location(player.getUniqueId());
        server.getScheduler().performOneTick();
        NetworkLocation location = future.get(5, TimeUnit.SECONDS);

        assertThat(location.serverName()).isEqualTo("lobby-1");
        assertThat(location.worldName()).isEqualTo(player.getWorld().getName());
        assertThat(location.x()).isEqualTo(player.getLocation().getX());
    }

    private PantheonRuntime runtime() {
        return pantheon.runtime();
    }

    private BootstrapResult.Started started() {
        return runtime().started();
    }

    private InMemoryNetwork network() {
        Optional<InMemoryNetwork> n = started().network();
        return n.orElseThrow();
    }
}
