package de.kalypzo.pantheon.core.player;

import static de.kalypzo.pantheon.language.Arg.arg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kalypzo.pantheon.core.audit.NoAudit;
import de.kalypzo.pantheon.core.backend.Delivered;
import de.kalypzo.pantheon.core.backend.DeliveryKind;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.language.LanguageConfigImpl;
import de.kalypzo.pantheon.core.language.LanguageEntry;
import de.kalypzo.pantheon.core.language.LanguageHooks;
import de.kalypzo.pantheon.core.language.LanguageImpl;
import de.kalypzo.pantheon.core.language.LocaleResolver;
import de.kalypzo.pantheon.core.language.Renderer;
import de.kalypzo.pantheon.core.language.ArgAdapters;
import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.language.LanguageProvider;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.language.MessageType;
import de.kalypzo.pantheon.player.ExpectedOnlinePlayerException;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.SessionData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The network-wide PanPlayer (player.md §2-§4, §10; R9, R42, E2). */
class NetworkPanPlayerTest {

    private static final Locale DE = Locale.of("de", "DE");
    private static final Locale EN = Locale.of("en", "US");
    private static final MessageKey HELLO = MessageKey.of("hello");

    private InMemoryNetwork net;
    private InMemoryNetwork.Node node;
    private PresenceCache presence;
    private final UUID id = UUID.randomUUID();
    private final AtomicReference<LocalPanPlayer> local = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        net = new InMemoryNetwork();
        node = net.node("lobby-1");
        presence = new PresenceCache(node);
    }

    @AfterEach
    void tearDown() {
        net.close();
    }

    private static LanguageImpl language(String plugin, String de, String en) {
        LanguageConfigImpl config = new LanguageConfigImpl(plugin);
        Renderer renderer = new Renderer(plugin,
                new LocaleResolver(Map.of(DE, Map.of("hello", new LanguageEntry.Text(de)),
                        EN, Map.of("hello", new LanguageEntry.Text(en)))),
                new ArgAdapters(), Logger.getAnonymousLogger(), config::defaultLocale);
        return new LanguageImpl(plugin, config, new LanguageHooks(), () -> renderer, () -> {});
    }

    private NetworkPanPlayer player(LanguageImpl language, LanguageProvider provider) {
        return new NetworkPanPlayer(id, presence, u -> Optional.ofNullable(local.get()), node, node, language,
                provider, Runnable::run, u -> NoAudit.INSTANCE);
    }

    private void joinRemote(Locale locale) {
        net.join(new PresenceEntry(id, "Alice", "lobby-2", "proxy-1", locale, Instant.EPOCH));
        org.awaitility.Awaitility.await().until(() -> presence.get(id).isPresent());
    }

    private LanguageProvider presenceLocale() {
        return p -> presence.get(id).map(PresenceEntry::locale).orElse(DE);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void remotePlayerMessagesGoThroughBackend() {
        joinRemote(EN);
        NetworkPanPlayer p = player(language("lobby", "Hallo <name>", "Hello <name>"), presenceLocale());
        p.sendMessage(HELLO, arg("name", "Bob"));
        List<Delivered> sent = net.deliveries("proxy-1");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).player()).isEqualTo(id);
        assertThat(sent.get(0).delivery().kind()).isEqualTo(DeliveryKind.CHAT);
        assertThat(plain(sent.get(0).delivery().content())).endsWith("Hello Bob");
        p.sendActionBar(HELLO, arg("name", "Bob"));
        p.showTitle(HELLO, arg("name", "Bob"));
        assertThat(net.deliveries("proxy-1")).extracting(d -> d.delivery().kind())
                .containsExactly(DeliveryKind.CHAT, DeliveryKind.ACTION_BAR, DeliveryKind.TITLE);
    }

    @Test
    void offlineMessageIsDroppedAndLogged() {
        List<String> logs = new ArrayList<>();
        Logger log = Logger.getLogger(NetworkPanPlayer.class.getName());
        Level old = log.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel().intValue() <= Level.FINE.intValue()) logs.add(r.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        log.setLevel(Level.ALL);
        log.addHandler(handler);
        try {
            NetworkPanPlayer p = player(language("lobby", "Hallo", "Hello"), LanguageProvider.defaultLocale());
            p.sendMessage(HELLO);
            assertThat(net.deliveries("proxy-1")).isEmpty();
            assertThat(logs).contains("Nachricht an " + id + " verworfen: offline");
            assertThat(p.audience()).isSameAs(Audience.empty());
        } finally {
            log.removeHandler(handler);
            log.setLevel(old);
        }
    }

    @Test
    void localeFromProvider() {
        LanguageImpl lang = language("lobby", "Hallo", "Hello");
        assertThat(player(lang, p -> EN).lang()).isEqualTo(EN);
        // the marker becomes the configured default language
        assertThat(player(lang, LanguageProvider.defaultLocale()).lang()).isEqualTo(lang.defaultLocale());
    }

    @Test
    void localePointerIsLang() {
        NetworkPanPlayer p = player(language("lobby", "Hallo", "Hello"), x -> EN);
        joinRemote(EN);
        assertThat(p.pointers().get(Identity.LOCALE)).contains(EN);
        assertThat(p.pointers().get(Identity.UUID)).contains(id);
        assertThat(p.pointers().get(Identity.NAME)).contains("Alice");
    }

    @Test
    void handleSurvivesQuit() {
        FakeLocal fake = new FakeLocal();
        local.set(fake);
        NetworkPanPlayer p = player(language("lobby", "Hallo", "Hello"), LanguageProvider.defaultLocale());
        assertThat(p.isLocal()).isTrue();
        assertThat(p.local()).containsSame(fake);
        assertThat(p.isOnline()).isTrue();
        local.set(null); // the quit
        assertThat(p.isLocal()).isFalse();
        assertThat(p.local()).isEmpty();
        assertThat(p.isOnline()).isFalse();
        assertThat(p.uniqueId()).isEqualTo(id);
    }

    @Test
    void rendersWithPluginLanguageOfInstance() {
        joinRemote(DE);
        NetworkPanPlayer a = player(language("alpha", "Alpha <name>", "Alpha <name>"), presenceLocale());
        NetworkPanPlayer b = player(language("beta", "Beta <name>", "Beta <name>"), presenceLocale());
        a.sendMessage(HELLO, arg("name", "x"));
        b.sendMessage(HELLO, arg("name", "x"));
        assertThat(net.deliveries("proxy-1")).extracting(d -> plain(d.delivery().content()))
                .satisfiesExactly(m -> assertThat(m).endsWith("Alpha x"), m -> assertThat(m).endsWith("Beta x"));
    }

    @Test
    void locationOfOfflineFails() {
        NetworkPanPlayer p = player(language("lobby", "Hallo", "Hello"), LanguageProvider.defaultLocale());
        assertThatThrownBy(() -> p.location().get()).isInstanceOf(ExecutionException.class)
                .cause().isInstanceOfSatisfying(ExpectedOnlinePlayerException.class,
                        e -> assertThat(e.scope()).isEqualTo(ExpectedOnlinePlayerException.Scope.NETWORK));
    }

    @Test
    void onlineIncludesLocalBeforePresenceUpdate() {
        FakeLocal fake = new FakeLocal();
        local.set(fake);
        NetworkPanPlayer p = player(language("lobby", "Hallo", "Hello"), LanguageProvider.defaultLocale());
        assertThat(presence.get(id)).isEmpty();
        assertThat(p.isOnline()).isTrue();
        assertThat(p.audience()).isSameAs(fake);
        p.sendMessage(HELLO);
        assertThat(fake.messages).hasSize(1);
        assertThat(net.deliveries("proxy-1")).isEmpty();
    }

    @Test
    void remotePlayerIsOnlineOnItsServer() {
        joinRemote(DE);
        NetworkPanPlayer p = player(language("lobby", "Hallo", "Hello"), LanguageProvider.defaultLocale());
        assertThat(p.server()).contains("lobby-2");
        assertThat(p.isOnline()).isTrue();
        assertThat(p.isLocal()).isFalse();
    }

    @Test
    void equalityIsByUuidAcrossViews() {
        LanguageImpl lang = language("lobby", "Hallo", "Hello");
        NetworkPanPlayer a = player(lang, LanguageProvider.defaultLocale());
        NetworkPanPlayer b = player(language("other", "x", "y"), p -> EN);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(java.util.Set.<de.kalypzo.pantheon.player.PanPlayer>of(a)).contains(b);
        assertThat(a).isNotEqualTo(new NetworkPanPlayer(UUID.randomUUID(), presence, u -> Optional.empty(), node,
                node, lang, LanguageProvider.defaultLocale(), Runnable::run, u -> NoAudit.INSTANCE));
    }

    @Test
    void auditIsTheLogOfItsUuid() {
        List<UUID> asked = new ArrayList<>();
        NetworkPanPlayer p = new NetworkPanPlayer(id, presence, u -> Optional.empty(), node, node,
                language("lobby", "Hallo", "Hello"), LanguageProvider.defaultLocale(), Runnable::run, u -> {
                    asked.add(u);
                    return NoAudit.INSTANCE;
                });
        assertThat(p.audit()).isSameAs(NoAudit.INSTANCE); // offline too: the log is for any UUID (audit §4)
        assertThat(asked).containsExactly(id);
    }

    private NetworkPanPlayer playerOn(String nodeId) {
        return new NetworkPanPlayer(id, presence, u -> Optional.ofNullable(local.get()), node, node,
                language("lobby", "x", "y"), LanguageProvider.defaultLocale(), Runnable::run, null, nodeId,
                u -> NoAudit.INSTANCE);
    }

    @Test
    void teleportLocalToOwnServerUsesHandle() {
        FakeLocal handle = new FakeLocal();
        handle.teleportResult = de.kalypzo.pantheon.world.TeleportResult.WORLD_NOT_FOUND;
        local.set(handle);
        var target = new de.kalypzo.pantheon.world.NetworkLocation("lobby-01", "world", 1, 2, 3, 0, 0);
        assertThat(playerOn("lobby-01").teleport(target).join())
                .isEqualTo(de.kalypzo.pantheon.world.TeleportResult.WORLD_NOT_FOUND);
        assertThat(handle.teleports).containsExactly(target);
    }

    @Test
    void teleportOfflineIsNotOnline() {
        var target = new de.kalypzo.pantheon.world.NetworkLocation("lobby-02", "world", 1, 2, 3, 0, 0);
        assertThat(playerOn("lobby-01").teleport(target).join())
                .isEqualTo(de.kalypzo.pantheon.world.TeleportResult.NOT_ONLINE);
    }

    @Test
    void teleportRemoteGoesToBackend() {
        joinRemote(EN);
        var target = new de.kalypzo.pantheon.world.NetworkLocation("lobby-2", "world", 1, 2, 3, 0, 0);
        assertThat(playerOn("lobby-01").teleport(target).join())
                .isEqualTo(de.kalypzo.pantheon.world.TeleportResult.SERVER_UNAVAILABLE);
    }

    @Test
    void connectOwnServerIsOk() {
        local.set(new FakeLocal());
        assertThat(playerOn("lobby-01").connect("lobby-01").join())
                .isEqualTo(de.kalypzo.pantheon.world.TeleportResult.OK);
    }

    /** A local player: it is its own audience and records messages. */
    private static final class FakeLocal implements LocalPanPlayer {
        final List<Component> messages = new ArrayList<>();
        private final Audience audience = new Audience() {
            @Override
            public void sendMessage(Component message) {
                messages.add(message);
            }
        };

        @Override
        public Audience audience() {
            return audience;
        }

        @Override
        public Player bukkit() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasPermission(String permission) {
            return false;
        }

        @Override
        public Doc data() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Optional<T> session(SessionData<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> void session(SessionData<T> key, T value) {}

        @Override
        public de.kalypzo.pantheon.audit.AuditLog audit() {
            return NoAudit.INSTANCE; // these tests do not log
        }

        @Override
        public UUID uniqueId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String name() {
            return "Local";
        }

        @Override
        public Locale lang() {
            return DE;
        }

        @Override
        public boolean isOnline() {
            return true;
        }

        @Override
        public boolean isLocal() {
            return true;
        }

        @Override
        public Optional<String> server() {
            return Optional.of("lobby-1");
        }

        @Override
        public Optional<LocalPanPlayer> local() {
            return Optional.of(this);
        }

        final List<Object> teleports = new ArrayList<>();
        de.kalypzo.pantheon.world.TeleportResult teleportResult = de.kalypzo.pantheon.world.TeleportResult.OK;

        @Override
        public CompletableFuture<de.kalypzo.pantheon.world.TeleportResult> teleport(
                de.kalypzo.pantheon.world.NetworkLocation location) {
            teleports.add(location);
            return CompletableFuture.completedFuture(teleportResult);
        }

        @Override
        public CompletableFuture<de.kalypzo.pantheon.world.TeleportResult> connect(String server) {
            teleports.add(server);
            return CompletableFuture.completedFuture(teleportResult);
        }

        @Override
        public CompletableFuture<de.kalypzo.pantheon.world.NetworkLocation> location() {
            return CompletableFuture.completedFuture(
                    new de.kalypzo.pantheon.world.NetworkLocation("lobby-1", "world", 1, 2, 3, 0, 0));
        }

        @Override
        public void sendMessage(MessageKey key, de.kalypzo.pantheon.language.Arg... args) {}

        @Override
        public void sendActionBar(MessageKey key, de.kalypzo.pantheon.language.Arg... args) {}

        @Override
        public void showTitle(MessageKey key, de.kalypzo.pantheon.language.Arg... args) {}
    }
}
