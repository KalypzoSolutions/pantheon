package de.kalypzo.pantheon.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.Pantheon;
import de.kalypzo.pantheon.core.backend.DeliveryKind;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.CommonMessage;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.PanPlayer;
import de.kalypzo.pantheon.player.PlayerService;
import de.kalypzo.pantheon.spi.PantheonProvider;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** PanPlayer and messages on Paper (P-25; player.md §2-§4, §10; language.md §4, §8). */
class PaperPlayerServiceTest {

    private static final DataNamespace LOBBY = DataNamespace.of("lobby");
    private static final Field<Long> COINS = LOBBY.ofLong("coins", 0L);

    private ServerMock server;
    private PantheonPlugin pantheon;
    private PlayerService players;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PantheonPlugin.environment = () -> Map.of("PANTHEON_BACKEND", "memory", "PANTHEON_NODE_ID", "lobby-1");
        pantheon = MockBukkit.load(PantheonPlugin.class);
        JavaPlugin other = MockBukkit.createMockPlugin("Other");
        players = Pantheon.get(other).players();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        PantheonPlugin.environment = System::getenv;
        PantheonProvider.unregister();
    }

    private InMemoryNetwork network() {
        return pantheon.runtime().started().network().orElseThrow();
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void localNeverFailsAndMessagesByKeyReachThePlayer() {
        PlayerMock player = server.addPlayer("Notch");
        LocalPanPlayer local = players.local(player);
        assertThat(local.bukkit()).isSameAs(player);
        local.sendMessage(CommonMessage.DATA_UNAVAILABLE);
        assertThat(plain(player.nextComponentMessage())).contains("Spielerdaten");
        // a player that never joined through Pantheon: a handle of an ended session, never null
        PlayerMock stranger = new PlayerMock(server, "Stranger", UUID.randomUUID());
        LocalPanPlayer ended = players.local(stranger);
        assertThat(ended.isLocal()).isFalse();
        assertThat(ended.local()).isEmpty();
    }

    @Test
    void onlineFindsLocalAndRemotePlayers() {
        PlayerMock player = server.addPlayer("Notch");
        UUID remote = UUID.randomUUID();
        network().join(new PresenceEntry(remote, "Alex", "lobby-2", "proxy-1", Locale.GERMANY, Instant.EPOCH));
        await().until(() -> pantheon.runtime().started().presence().get(remote).isPresent());

        assertThat(players.online(player.getUniqueId())).get().extracting(PanPlayer::isLocal).isEqualTo(true);
        assertThat(players.online("notch")).isPresent();
        PanPlayer alex = players.online("ALEX").orElseThrow();
        assertThat(alex.uniqueId()).isEqualTo(remote);
        assertThat(alex.isLocal()).isFalse();
        assertThat(alex.server()).contains("lobby-2");
        assertThat(players.online(UUID.randomUUID())).isEmpty();
        assertThat(players.online()).extracting(PanPlayer::name).containsExactlyInAnyOrder("Notch", "Alex");
        assertThat(players.onlineLocal()).extracting(PanPlayer::name).containsExactly("Notch");

        alex.sendMessage(CommonMessage.DATA_UNAVAILABLE);
        // an ERROR message is a chat line and its sound, both for the remote player, in order
        assertThat(network().deliveries("proxy-1")).extracting(d -> d.player(), d -> d.delivery().kind())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(remote, DeliveryKind.CHAT),
                        org.assertj.core.groups.Tuple.tuple(remote, DeliveryKind.SOUND));
    }

    @Test
    void onChangeHandsOverTheLocalPlayerOnly() throws Exception {
        PlayerMock player = server.addPlayer("Notch");
        List<UUID> seen = new CopyOnWriteArrayList<>();
        players.onChange(COINS, (p, before, after) -> seen.add(p.uniqueId()));
        players.local(player).data().inc(COINS, 3L);
        for (int i = 0; i < 100 && seen.isEmpty(); i++) { // listeners run on the main thread (E15)
            server.getScheduler().performOneTick();
            Thread.sleep(20);
        }
        assertThat(seen).containsExactly(player.getUniqueId());
    }

    @Test
    void playerAndOfflinePlayerArgsAreAdapted() {
        PlayerMock player = server.addPlayer("Notch");
        var language = Pantheon.get(MockBukkit.createMockPlugin("Third")).language();
        assertThat(language.defaultLocale()).isNotNull();
        // the adapters are registered for every plugin: a Player renders as its display name
        players.local(player).sendMessage(CommonMessage.DATA_UNAVAILABLE, Arg.arg("who", player));
        assertThat(player.nextComponentMessage()).isNotNull();
    }

    @Test
    void viewsAreEqualByUuidAndLocalFollowsRejoin() {
        PlayerMock player = server.addPlayer("Notch");
        UUID uuid = player.getUniqueId();
        LocalPanPlayer view = players.local(player);
        PanPlayer online = players.online(uuid).orElseThrow();
        PanPlayer byName = players.online("Notch").orElseThrow();
        java.util.Set<PanPlayer> set = new java.util.HashSet<>(List.of(view, online, byName));
        set.addAll(players.online());
        set.addAll(players.onlineLocal());
        assertThat(set).hasSize(1);
        assertThat(set).contains(pantheon.runtime().localPlayers().get(uuid).orElseThrow());

        player.disconnect();
        assertThat(view.isLocal()).isFalse();
        assertThat(view.local()).isEmpty();
        assertThat(set).contains(view);

        server.addPlayer(player);
        PlayerMock again = player;
        LocalPanPlayer current = view.local().orElseThrow(); // the view of the new session
        assertThat(current.bukkit()).isSameAs(again);
        assertThat(current.data()).isNotNull();
        assertThat(view).isEqualTo(current);
    }
}
