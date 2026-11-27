package de.kalypzo.pantheon.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.Pantheon;
import de.kalypzo.pantheon.audit.AuditAction;
import de.kalypzo.pantheon.audit.AuditActions;
import de.kalypzo.pantheon.audit.AuditLog;
import de.kalypzo.pantheon.audit.AuditNamespace;
import de.kalypzo.pantheon.audit.AuditPosition;
import de.kalypzo.pantheon.audit.AuditTrace;
import de.kalypzo.pantheon.audit.TimelineEntry;
import de.kalypzo.pantheon.audit.TimelinePage;
import de.kalypzo.pantheon.core.audit.AuditBackend;
import de.kalypzo.pantheon.core.audit.AuditBundle;
import de.kalypzo.pantheon.core.audit.AuditRecord;
import de.kalypzo.pantheon.core.audit.Position;
import de.kalypzo.pantheon.core.audit.StoredAuditRecord;
import de.kalypzo.pantheon.core.audit.TimelineResult;
import de.kalypzo.pantheon.core.audit.TimelineSpec;
import de.kalypzo.pantheon.core.audit.Viewer;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.core.data.UuidV7;
import de.kalypzo.pantheon.core.runtime.BootstrapResult;
import de.kalypzo.pantheon.paper.audit.PaperTimelineQuery;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.PanPlayer;
import de.kalypzo.pantheon.spi.PantheonProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** The audit API on Paper and its wiring into the runtime (P-36; audit_log.md §4, §7, §10, E36, E37, E41). */
class PaperAuditWiringTest {

    private static final AuditNamespace SHOP = AuditNamespace.of("shop");
    private static final AuditAction OPEN = SHOP.action("open");
    private static final AuditAction BUY = SHOP.action("buy");
    private static final AuditAction CLOSE = SHOP.action("close");
    private static final AuditAction PAY = AuditNamespace.of("economy").action("pay");
    private static final UUID NIL = new UUID(0, 0);

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

    private PantheonRuntime runtime() {
        return pantheon.runtime();
    }

    private BootstrapResult.Started started() {
        return runtime().started();
    }

    private InMemoryNetwork network() {
        return started().network().orElseThrow();
    }

    /**
     * Sends what the node's buffer holds and reads the plugin entries of a timeline, oldest first. This CLOSES the
     * buffer for good (AuditBuffer has no flush): entries logged afterwards are dropped, so log everything first.
     */
    private List<StoredAuditRecord> closeBufferAndRead(UUID player) throws Exception {
        runtime().auditBuffer().close(Duration.ofSeconds(2));
        return timeline(network(), player);
    }

    private static List<StoredAuditRecord> timeline(InMemoryNetwork network, UUID player) throws Exception {
        Instant now = Instant.now(); // explicit window: the default `to` (now, exclusive) hides this millisecond
        TimelineResult page = network.node("reader").timeline(new TimelineSpec(player, now.minusSeconds(3600),
                now.plusSeconds(3600),
                List.of("shop:*", "economy:*", "pantheon:chat"), 500, null, null,
                new Viewer(null, "test", "test"))).get(5, TimeUnit.SECONDS);
        List<StoredAuditRecord> entries = new ArrayList<>(page.entries());
        return entries.reversed();
    }

    private static List<String> actions(List<StoredAuditRecord> entries) {
        return entries.stream().map(e -> e.record().action()).toList();
    }

    private WorldMock world() {
        return server.addSimpleWorld("world");
    }

    @Test
    void actionReachesBufferWithActiveTrace() throws Exception {
        PlayerMock notch = server.addPlayer("Notch");
        UUID uuid = notch.getUniqueId();
        LocalPanPlayer local = api.getPlayer(notch);
        Instant before = Instant.now().minusMillis(1);

        AuditTrace trace = local.audit().begin(OPEN, d -> {});
        local.audit().action(PAY, d -> d.with("amount", 250));
        api.audit().player(uuid).action(BUY, d -> d.with("item", "diamond_sword").with("price", 2.5)
                .with("sale", true));
        local.audit().action(AuditActions.CHAT, d -> d.with("text", "hi")); // pantheon:* never gets a trace

        List<StoredAuditRecord> entries = closeBufferAndRead(uuid);
        assertThat(actions(entries)).containsExactly("shop:open", "economy:pay", "shop:buy", "pantheon:chat");
        assertThat(entries).extracting(e -> e.record().trace())
                .containsExactly(trace.id(), trace.id(), trace.id(), null);
        assertThat(entries).extracting(StoredAuditRecord::server).containsOnly("lobby-1");
        assertThat(entries).extracting(e -> e.record().player()).containsOnly(uuid);
        assertThat(entries.get(1).record().with()).containsEntry("amount", 250L);
        assertThat(entries.get(2).record().with()).containsEntry("item", "diamond_sword")
                .containsEntry("price", 2.5).containsEntry("sale", true);
        for (StoredAuditRecord e : entries) { // UUIDv7 of the runtime clock
            assertThat(e.record().id().version()).isEqualTo(7);
            assertThat(UuidV7.timestamp(e.record().id())).isAfter(before).isBefore(Instant.now().plusSeconds(1));
        }
    }

    @Test
    void beginLogsOpenWithNewTrace() throws Exception {
        PlayerMock notch = server.addPlayer("Notch");
        AuditLog audit = api.getPlayer(notch).audit();

        AuditTrace first = audit.begin(OPEN, d -> d.with("shop", "a"));
        AuditTrace second = audit.begin(OPEN, d -> d.with("shop", "b")); // ends the first without logging

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.id().version()).isEqualTo(7);
        assertThat(first.isActive()).isFalse();
        assertThat(second.isActive()).isTrue();
        assertThat(audit.activeTrace()).get().extracting(AuditTrace::id).isEqualTo(second.id());
        List<StoredAuditRecord> entries = closeBufferAndRead(notch.getUniqueId());
        assertThat(actions(entries)).containsExactly("shop:open", "shop:open");
        assertThat(entries).extracting(e -> e.record().trace()).containsExactly(first.id(), second.id());
    }

    @Test
    void beginWithFailingDescriptorLeavesNoTrace() throws Exception {
        PlayerMock notch = server.addPlayer("Notch");
        AuditLog audit = api.getPlayer(notch).audit();

        assertThatThrownBy(() -> audit.begin(OPEN, d -> d.with("Bad-Key", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        audit.action(PAY);

        assertThat(audit.activeTrace()).isEmpty();
        List<StoredAuditRecord> entries = closeBufferAndRead(notch.getUniqueId());
        assertThat(actions(entries)).containsExactly("economy:pay");
        assertThat(entries.getFirst().record().trace()).isNull();
    }

    @Test
    void endLogsCloseThenEnds() throws Exception {
        PlayerMock notch = server.addPlayer("Notch");
        AuditLog audit = api.getPlayer(notch).audit();

        AuditTrace trace = audit.begin(OPEN, d -> {});
        trace.end(CLOSE, d -> d.with("items", 3));
        audit.action(BUY);

        assertThat(trace.isActive()).isFalse();
        assertThat(audit.activeTrace()).isEmpty();
        List<StoredAuditRecord> entries = closeBufferAndRead(notch.getUniqueId());
        assertThat(actions(entries)).containsExactly("shop:open", "shop:close", "shop:buy");
        assertThat(entries).extracting(e -> e.record().trace()).containsExactly(trace.id(), trace.id(), null);
        assertThat(entries.get(1).record().with()).containsEntry("items", 3L);

        AuditTrace other = audit.begin(OPEN, d -> {});
        trace.end(); // an ended trace does not end the newer one
        assertThat(other.isActive()).isTrue();
        other.end();
        assertThat(other.isActive()).isFalse();
    }

    @Test
    void entityPlayerBecomesTarget() throws Exception {
        WorldMock world = world();
        PlayerMock notch = server.addPlayer("Notch");
        PlayerMock bob = server.addPlayer("Bob");
        bob.teleport(new Location(world, 1.5, 70, 2.5));
        Villager npc = (Villager) world.spawnEntity(new Location(world, 12, 64, -3), EntityType.VILLAGER);
        npc.customName(Component.text("Händler", NamedTextColor.GOLD));
        AuditLog audit = api.getPlayer(notch).audit();

        audit.action(OPEN, d -> d.entity(npc));
        audit.action(BUY, d -> d.entity(bob).with("items", 3));

        List<StoredAuditRecord> entries = closeBufferAndRead(notch.getUniqueId());
        AuditRecord open = entries.get(0).record();
        assertThat(open.position()).isEqualTo(new Position("world", 12, 64, -3));
        assertThat(open.target()).isNull();
        assertThat(open.with()).containsEntry("entity_type", "villager")
                .containsEntry("entity_uuid", npc.getUniqueId().toString())
                .containsEntry("entity_name", "Händler");
        AuditRecord trade = entries.get(1).record();
        assertThat(trade.target()).isEqualTo(bob.getUniqueId());
        assertThat(trade.position()).isEqualTo(new Position("world", 1.5, 70, 2.5));
        assertThat(trade.with()).containsEntry("entity_type", "player").containsEntry("entity_name", "Bob")
                .containsEntry("entity_uuid", bob.getUniqueId().toString());
        // lands in both timelines
        assertThat(timeline(network(), bob.getUniqueId())).extracting(e -> e.record().id())
                .containsExactly(trade.id());
    }

    @Test
    void lastOfEntityAndTargetWins() throws Exception {
        WorldMock world = world();
        PlayerMock notch = server.addPlayer("Notch");
        PlayerMock bob = server.addPlayer("Bob");
        UUID alice = UUID.randomUUID();
        Villager npc = (Villager) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.VILLAGER);
        AuditLog audit = api.getPlayer(notch).audit();

        audit.action(OPEN, d -> d.target(alice).entity(bob));
        audit.action(BUY, d -> d.entity(bob).target(alice));
        audit.action(CLOSE, d -> d.entity(bob).entity(npc)); // the villager has no name: entity_name goes
        audit.action(PAY, d -> d.target(api.getPlayer(bob)).location(
                new de.kalypzo.pantheon.world.NetworkLocation("lobby-2", "nether", 1, 2, 3, 0, 0)));

        List<StoredAuditRecord> entries = closeBufferAndRead(notch.getUniqueId());
        assertThat(entries).extracting(e -> e.record().target())
                .containsExactly(bob.getUniqueId(), alice, bob.getUniqueId(), bob.getUniqueId());
        assertThat(entries.get(2).record().with()).containsEntry("entity_type", "villager")
                .doesNotContainKey("entity_name");
        assertThat(entries.get(3).record().position()).isEqualTo(new Position("nether", 1, 2, 3));
    }

    @Test
    void consoleViewerHasNilUuid() throws Exception {
        List<TimelineSpec> calls = new CopyOnWriteArrayList<>();
        UUID target = UUID.randomUUID();
        UUID id = UuidV7.next(Clock.systemUTC());
        UUID trace = UuidV7.next(Clock.systemUTC());
        AuditRecord stored = new AuditRecord(id, target, "shop:buy", new Position("world", 1, 2, 3), null, trace,
                Map.of("item", "apple"));
        AuditBackend backend = backend(calls, new TimelineResult(List.of(new StoredAuditRecord(stored, "lobby-2")),
                id));

        TimelinePage page = new PaperTimelineQuery(backend, target).viewer(server.getConsoleSender())
                .between(Instant.ofEpochSecond(100), Instant.ofEpochSecond(200))
                .actions(BUY).namespace(AuditNamespace.of("economy")).limit(9999).fetch()
                .get(5, TimeUnit.SECONDS);

        TimelineSpec spec = calls.getFirst();
        assertThat(spec.viewer()).isEqualTo(new Viewer(NIL, server.getConsoleSender().getName(), "command"));
        assertThat(spec.player()).isEqualTo(target);
        assertThat(spec.from()).isEqualTo(Instant.ofEpochSecond(100));
        assertThat(spec.to()).isEqualTo(Instant.ofEpochSecond(200));
        assertThat(spec.actions()).containsExactly("shop:buy", "economy:*");
        assertThat(spec.limit()).isEqualTo(500); // E33
        assertThat(page.cursor()).contains(id);
        assertThat(page.entries()).singleElement().isEqualTo(new TimelineEntry(id, UuidV7.timestamp(id), target,
                "lobby-2", "shop:buy", Optional.of(new AuditPosition("world", 1, 2, 3)), Optional.empty(),
                Optional.of(trace), Map.of("item", "apple")));

        PlayerMock notch = server.addPlayer("Notch");
        new PaperTimelineQuery(backend, target).viewer(notch).fetch().get(5, TimeUnit.SECONDS);
        new PaperTimelineQuery(backend, target).viewer(null, "web:admin", "web").after(id).fetch()
                .get(5, TimeUnit.SECONDS);
        assertThat(calls.get(1).viewer()).isEqualTo(new Viewer(notch.getUniqueId(), "Notch", "command"));
        assertThat(calls.get(1).limit()).isEqualTo(100); // E33 default
        assertThat(calls.get(2).viewer()).isEqualTo(new Viewer(NIL, "web:admin", "web"));
        assertThat(calls.get(2).cursor()).isEqualTo(id);
    }

    @Test
    void fetchWithoutViewerFails() {
        List<TimelineSpec> calls = new CopyOnWriteArrayList<>();
        AuditBackend backend = backend(calls, new TimelineResult(List.of(), null));

        assertThatThrownBy(() -> new PaperTimelineQuery(backend, UUID.randomUUID()).limit(10).fetch())
                .isInstanceOf(IllegalStateException.class).hasMessage("viewer fehlt");
        assertThatThrownBy(() -> api.audit().timeline(UUID.randomUUID()).fetch())
                .isInstanceOf(IllegalStateException.class).hasMessage("viewer fehlt");
        assertThat(calls).isEmpty();
    }

    @Test
    void quitEndsTracesAtMonitor() throws Exception {
        PlayerMock notch = server.addPlayer("Notch");
        UUID uuid = notch.getUniqueId();
        AtomicReference<Optional<UUID>> atHighest = new AtomicReference<>();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST)
            public void onQuit(PlayerQuitEvent event) { // review focus 2: a shop logs in the quit event
                AuditLog audit = api.getPlayer(event.getPlayer()).audit();
                atHighest.set(audit.activeTrace().map(AuditTrace::id));
                audit.action(CLOSE);
            }
        }, shop);
        AuditTrace session = api.getPlayer(notch).audit().begin(OPEN, d -> {});

        notch.disconnect();

        assertThat(atHighest.get()).contains(session.id());
        assertThat(session.isActive()).isFalse();
        assertThat(api.audit().player(uuid).activeTrace()).isEmpty();
        assertThat(runtime().traces().active(uuid)).isEmpty();
        List<StoredAuditRecord> entries = closeBufferAndRead(uuid);
        assertThat(actions(entries)).containsExactly("shop:open", "shop:close");
        assertThat(entries).extracting(e -> e.record().trace()).containsOnly(session.id());
    }

    @Test
    void remotePlayerAuditUsesSameBuffer() throws Exception {
        PlayerMock notch = server.addPlayer("Notch");
        UUID remote = UUID.randomUUID();
        network().join(new PresenceEntry(remote, "Alex", "lobby-2", "proxy-1", Locale.GERMANY, Instant.EPOCH));
        await().until(() -> started().presence().get(remote).isPresent());
        PanPlayer alex = api.players().online(remote).orElseThrow();
        PanPlayer alexForOther = Pantheon.get(MockBukkit.createMockPlugin("Other")).players().online(remote)
                .orElseThrow();
        assertThat(alex.isLocal()).isFalse();

        // a team member here bans someone who plays elsewhere
        alex.audit().action(BUY, d -> d.target(api.getPlayer(notch)));
        alexForOther.audit().action(PAY, d -> d.with("amount", 1));
        api.getPlayer(notch).audit().action(CLOSE);

        List<StoredAuditRecord> entries = closeBufferAndRead(remote);
        assertThat(actions(entries)).containsExactly("shop:buy", "economy:pay");
        assertThat(entries).extracting(StoredAuditRecord::server).containsOnly("lobby-1");
        assertThat(entries).extracting(e -> e.record().player()).containsOnly(remote);
        assertThat(actions(timeline(network(), notch.getUniqueId()))).containsExactly("shop:buy", "shop:close");
    }

    @Test
    void hiddenCommandsResolveThroughTheCommandMap() {
        Command pay = new Command("secret", "", "/secret", List.of("psst")) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return true;
            }
        };
        server.getCommandMap().register("shop", pay);
        assertThat(runtime().hiddenCommands().isHidden("psst")).isFalse();

        api.audit().hideCommand(pay);
        api.audit().hideCommand("Whisper");

        assertThat(runtime().hiddenCommands().isHidden("secret")).isTrue();
        assertThat(runtime().hiddenCommands().isHidden("shop:psst")).isTrue();
        assertThat(runtime().hiddenCommands().isHidden("whisper")).isTrue();
        assertThat(runtime().hiddenCommands().isHidden("/LOGIN")).isTrue();
        assertThat(runtime().hiddenCommands().isHidden("other")).isFalse();
    }

    @Test
    void disableFlushesTheBufferBeforeTheBackendCloses() throws Exception {
        PlayerMock notch = server.addPlayer("Notch");
        InMemoryNetwork network = network();
        api.getPlayer(notch).audit().action(OPEN);

        server.getPluginManager().disablePlugin(pantheon); // R14: the audit step of Shutdown.run

        assertThat(actions(timeline(network, notch.getUniqueId()))).containsExactly("shop:open");
    }

    private static AuditBackend backend(List<TimelineSpec> calls, TimelineResult result) {
        return new AuditBackend() {
            @Override
            public CompletableFuture<Void> append(AuditBundle bundle) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<TimelineResult> timeline(TimelineSpec spec) {
                calls.add(spec);
                return CompletableFuture.completedFuture(result);
            }
        };
    }
}
