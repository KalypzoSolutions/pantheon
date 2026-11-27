package de.kalypzo.pantheon.paper;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.Pantheon;
import de.kalypzo.pantheon.audit.AuditNamespace;
import de.kalypzo.pantheon.core.audit.StoredAuditRecord;
import de.kalypzo.pantheon.core.audit.TimelineResult;
import de.kalypzo.pantheon.core.audit.TimelineSpec;
import de.kalypzo.pantheon.core.audit.Viewer;
import de.kalypzo.pantheon.spi.PantheonProvider;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Chat and command entries of the node's players (P-37; audit_log.md §5, E35, review focus 4). */
class AuditListenerTest {

    private ServerMock server;
    private PantheonPlugin pantheon;
    private JavaPlugin shop;
    private Pantheon api;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PantheonPlugin.environment = () -> Map.of("PANTHEON_BACKEND", "memory", "PANTHEON_NODE_ID", "lobby-1");
        pantheon = MockBukkit.load(PantheonPlugin.class);
        shop = MockBukkit.createMockPlugin("Shop");
        api = Pantheon.get(shop);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        PantheonPlugin.environment = System::getenv;
        PantheonProvider.unregister();
    }

    private List<StoredAuditRecord> closeBufferAndRead(UUID player) throws Exception {
        var rt = pantheon.runtime();
        rt.auditBuffer().close(Duration.ofSeconds(2));
        Instant now = Instant.now();
        TimelineResult page = rt.started().network().orElseThrow().node("reader").timeline(new TimelineSpec(player,
                now.minusSeconds(3600), now.plusSeconds(3600), List.of("pantheon:chat", "pantheon:command", "shop:*"), 500, null, null,
                new Viewer(null, "test", "test"))).get(5, TimeUnit.SECONDS);
        return new ArrayList<>(page.entries()).reversed();
    }

    /** The async chat event as Paper fires it, with a formatted message (PlayerMock.chat only takes plain strings). */
    private void chat(PlayerMock player, Component message) {
        Thread t = new Thread(() -> fireChat(player, message), "async-chat");
        t.start();
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void fireChat(PlayerMock player, Component message) {
        AsyncChatEvent event = new AsyncChatEvent(true, player, new java.util.HashSet<>(),
                io.papermc.paper.chat.ChatRenderer.defaultRenderer(), message, message,
                null);
        server.getPluginManager().callEvent(event);
    }

    private PlayerMock player() {
        PlayerMock p = server.addPlayer("Notch");
        p.teleport(new Location(server.addSimpleWorld("arena"), 1.5, 64, -3.25));
        return p;
    }

    @Test
    void chatLoggedWithPlainTextAndLocation() throws Exception {
        PlayerMock notch = player();
        Component message = Component.text("hello ", NamedTextColor.RED)
                .append(Component.text("world", NamedTextColor.BLUE)
                        .hoverEvent(HoverEvent.showText(Component.text("tip"))));

        chat(notch, message);

        List<StoredAuditRecord> entries = closeBufferAndRead(notch.getUniqueId());
        assertThat(entries).hasSize(1);
        var r = entries.getFirst().record();
        assertThat(r.action()).isEqualTo("pantheon:chat");
        assertThat(r.with()).containsEntry("text", "hello world").containsEntry("cancelled", false);
        assertThat(r.position()).isNotNull();
        assertThat(r.position().world()).isEqualTo("arena");
        assertThat(r.position().x()).isEqualTo(1.5);
        assertThat(r.position().z()).isEqualTo(-3.25);
        assertThat(r.trace()).isNull();
        assertThat(r.id().version()).isEqualTo(7);
    }

    @Test
    void cancelledChatLogged() throws Exception {
        PlayerMock notch = player();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOW)
            void onChat(AsyncChatEvent e) {
                e.setCancelled(true);
            }
        }, shop);

        chat(notch, Component.text("muted"));

        List<StoredAuditRecord> entries = closeBufferAndRead(notch.getUniqueId());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().record().with()).containsEntry("text", "muted")
                .containsEntry("cancelled", true);
    }

    @Test
    void commandLogged() throws Exception {
        PlayerMock notch = player();
        server.getCommandMap().register("shop", new BukkitCommand("pay") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                return true;
            }
        });

        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerCommandPreprocessEvent(notch, "/pay Alex 5"));

        List<StoredAuditRecord> entries = closeBufferAndRead(notch.getUniqueId());
        assertThat(entries).hasSize(1);
        var r = entries.getFirst().record();
        assertThat(r.action()).isEqualTo("pantheon:command");
        assertThat(r.with()).containsEntry("line", "pay Alex 5").containsEntry("command", "pay")
                .containsEntry("cancelled", false).containsEntry("unknown", false);
        assertThat(r.position()).isNotNull();
        assertThat(r.trace()).isNull();
    }

    @Test
    void hiddenCommandNotLogged() throws Exception {
        PlayerMock notch = player();
        Command tell = new BukkitCommand("tell") {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                return true;
            }
        };
        server.getCommandMap().register("msgplugin", tell);
        api.audit().hideCommand(tell);

        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerCommandPreprocessEvent(notch, "/tell Alex secret"));
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerCommandPreprocessEvent(notch, "/LOGIN geheim"));

        assertThat(closeBufferAndRead(notch.getUniqueId())).isEmpty();
    }

    @Test
    void chatDuringTraceHasNoTrace() throws Exception {
        PlayerMock notch = player();
        var trace = api.audit().player(notch.getUniqueId())
                .begin(AuditNamespace.of("shop").action("open"), d -> {});

        chat(notch, Component.text("hi"));

        List<StoredAuditRecord> entries = closeBufferAndRead(notch.getUniqueId());
        assertThat(entries).extracting(e -> e.record().action()).containsExactly("shop:open", "pantheon:chat");
        assertThat(entries.get(0).record().trace()).isEqualTo(trace.id());
        assertThat(entries.get(1).record().trace()).isNull();
    }
}
