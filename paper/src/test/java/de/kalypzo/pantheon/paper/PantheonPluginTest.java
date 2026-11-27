package de.kalypzo.pantheon.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kalypzo.pantheon.Pantheon;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.language.CommonMessage;
import de.kalypzo.pantheon.spi.PantheonProvider;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import java.time.Duration;

import org.bukkit.plugin.PluginDescriptionFile;
import static org.awaitility.Awaitility.await;
import java.util.Locale;
import java.util.Map;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** The Paper plugin: bootstrap in onEnable, one instance per consumer, teardown on its disable (P-23). */
class PantheonPluginTest {

    private static final DataNamespace LOBBY = DataNamespace.of("lobby");
    private static final Field<Long> COINS = LOBBY.ofLong("coins", 0L);
    private static final StoreRef CLANS = new StoreRef("lobby", "clans");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        PantheonPlugin.environment = System::getenv;
        PantheonProvider.unregister();
    }

    @Test
    void memoryBackendEnablesAndHandsOutOneInstancePerPlugin() {
        PantheonPlugin pantheon = load(Map.of("PANTHEON_BACKEND", "memory"));
        assertThat(pantheon.isEnabled()).isTrue();
        assertThat(pantheon.getDataFolder().toPath().resolve("outbox.db")).exists();

        JavaPlugin lobby = MockBukkit.createMockPlugin("Lobby");
        JavaPlugin shop = MockBukkit.createMockPlugin("Shop");
        Pantheon a = Pantheon.get(lobby);
        assertThat(Pantheon.get(lobby)).isSameAs(a);
        assertThat(Pantheon.get(shop)).isNotSameAs(a);
        assertThat(a.language()).isNotSameAs(Pantheon.get(shop).language());
        assertThat(a.config().language()).isNotNull();
        assertThat(a.mainThread()).isInstanceOf(MainThreadExecutor.class);
    }

    @Test
    void consumerDisableTearsDownItsInstanceAfterItsOnDisable() {
        load(Map.of("PANTHEON_BACKEND", "memory"));
        ConsumerPlugin lobby = consumer();
        PantheonImpl a = (PantheonImpl) lobby.enabledWith;
        a.store(LOBBY, "clans").onChange(COINS, (doc, before, after) -> {});
        lobby.cached.doc("c1").inc(COINS, 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> lobby.cached.get("c1").isPresent());

        lobby.disableLikePaper(); // R51: event, then onDisable

        assertThat(lobby.disabledWith).isSameAs(a);          // no new instance in onDisable
        assertThat(lobby.cachedDocsInOnDisable).isEqualTo(1); // its cached store is still there
        assertThat(a.isClosed()).isFalse();
        assertThat(Pantheon.get(lobby)).isSameAs(a);          // until the teardown tick

        server.getScheduler().performOneTick();

        assertThat(a.isClosed()).isTrue();
        assertThat(watched()).doesNotContain(CLANS);
        assertThatThrownBy(() -> Pantheon.get(lobby)).isInstanceOf(IllegalStateException.class);

        server.getPluginManager().enablePlugin(lobby);       // enabled again: a fresh instance
        assertThat(lobby.enabledWith).isNotSameAs(a);
        assertThat(Pantheon.get(lobby)).isSameAs(lobby.enabledWith);
        assertThat(((PantheonImpl) lobby.enabledWith).isClosed()).isFalse();
    }

    @Test
    void disableInBukkitOrderAlsoTearsDownOnTheNextTick() {
        load(Map.of("PANTHEON_BACKEND", "memory"));
        ConsumerPlugin lobby = consumer();
        PantheonImpl a = (PantheonImpl) lobby.enabledWith;

        server.getPluginManager().disablePlugin(lobby); // MockBukkit: onDisable, then the event

        assertThat(lobby.disabledWith).isSameAs(a);
        assertThat(lobby.cachedDocsInOnDisable).isZero();
        server.getScheduler().performOneTick();
        assertThat(a.isClosed()).isTrue();
        assertThat(watched()).doesNotContain(CLANS);
        assertThatThrownBy(() -> Pantheon.get(lobby)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pantheonDisableTearsDownRetiredInstancesAtOnce() {
        PantheonPlugin pantheon = load(Map.of("PANTHEON_BACKEND", "memory"));
        ConsumerPlugin lobby = consumer();
        PantheonImpl a = (PantheonImpl) lobby.enabledWith;
        lobby.disableLikePaper();

        server.getPluginManager().disablePlugin(pantheon);

        assertThat(a.isClosed()).isTrue();
    }

    @Test
    void consumerLanguageContainsPantheonMessages() {
        load(Map.of("PANTHEON_BACKEND", "memory"));
        JavaPlugin lobby = MockBukkit.createMockPlugin("Lobby");

        String text = Pantheon.get(lobby).language().message(CommonMessage.DATA_UNAVAILABLE)
                .plain(Locale.of("de", "DE"));

        assertThat(text).contains("Spielerdaten");
    }

    @Test
    void configErrorDisablesOnlyPantheonAndUnregistersProvider() {
        JavaPlugin lobby = MockBukkit.createMockPlugin("Lobby");
        PantheonPlugin pantheon = load(Map.of("PANTHEON_AGORA_ADDRESS", "agora:9090",
                "PANTHEON_AGORA_TOKEN", "t0p-s3cret"));

        assertThat(pantheon.isEnabled()).isFalse();
        // never Bukkit.shutdown(): the server and the other plugins keep running
        assertThat(MockBukkit.isMocked()).isTrue();
        assertThat(lobby.isEnabled()).isTrue();
        assertThatThrownBy(() -> Pantheon.get(lobby))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nicht geladen"); // R31: the provider itself is unregistered
        assertThat(PantheonPlugin.lastStartError).contains("PANTHEON_NODE_ID").doesNotContain("t0p-s3cret");
    }

    private ConsumerPlugin consumer() {
        return MockBukkit.loadWith(ConsumerPlugin.class,
                new PluginDescriptionFile("Lobby", "1.0", ConsumerPlugin.class.getName()));
    }

    private java.util.Collection<StoreRef> watched() {
        return ((PantheonPlugin) server.getPluginManager().getPlugin("Pantheon")).runtime().started().stores()
                .watchedStores();
    }

    private PantheonPlugin load(Map<String, String> env) {
        PantheonPlugin.environment = () -> env;
        return MockBukkit.load(PantheonPlugin.class);
    }
}
