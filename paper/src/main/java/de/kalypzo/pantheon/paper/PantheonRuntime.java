package de.kalypzo.pantheon.paper;

import de.kalypzo.pantheon.core.audit.AuditBuffer;
import de.kalypzo.pantheon.core.audit.HiddenCommands;
import de.kalypzo.pantheon.core.audit.TraceRegistry;
import de.kalypzo.pantheon.core.language.LanguageHooks;
import de.kalypzo.pantheon.core.language.ResourceLister;
import de.kalypzo.pantheon.core.runtime.BootstrapResult;
import de.kalypzo.pantheon.paper.audit.PaperAuditService;
import de.kalypzo.pantheon.paper.audit.PaperCommandResolver;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.PanPlayer;
import de.kalypzo.pantheon.player.PanPlayerFactory;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * What every plugin's {@link PantheonImpl} shares (megalodon §1: the expensive infrastructure exists once): the core
 * graph of {@link BootstrapResult.Started}, the main thread and Pantheon's own language files.
 *
 * <p>Seams for later work: P-24 sets a {@link #panPlayers(PanPlayerFactory) PanPlayer factory} for the players of
 * this server ({@link #localPlayers()}), P-25 left it as the handles of this server (the network-wide view is the
 * {@link PaperPlayerService} of each plugin); the login timeout and the {@code InMemoryNetwork} are in
 * {@link #started()}.
 *
 * <p>Audit (P-36, audit §7): one {@link AuditBuffer} per node on the runtime clock and scheduler, the node's
 * {@link TraceRegistry} and {@link HiddenCommands} (resolved through the server's command map), and the one
 * {@link PaperAuditService} that every plugin's {@code audit()} and every PanPlayer's {@code audit()} writes through.
 * {@link #close()} sends the buffer one last time in parallel to the flush (R14).
 */
public final class PantheonRuntime implements AutoCloseable {

    private static final PanPlayerFactory MISSING = new PanPlayerFactory() {
        @Override
        public LocalPanPlayer local(Player player) {
            throw missing();
        }

        @Override
        public Optional<PanPlayer> online(UUID uuid) {
            throw missing();
        }

        @Override
        public Optional<PanPlayer> online(String name) {
            throw missing();
        }

        @Override
        public Collection<PanPlayer> online() {
            throw missing();
        }

        @Override
        public Collection<LocalPanPlayer> onlineLocal() {
            throw missing();
        }

        private UnsupportedOperationException missing() {
            return new UnsupportedOperationException("PanPlayer is not available yet (P-24/P-25)");
        }
    };

    private final JavaPlugin plugin;
    private final BootstrapResult.Started started;
    private final MainThreadExecutor mainThread;
    private final ResourceLister pantheonResources;
    private final LocalPlayers localPlayers = new LocalPlayers();
    private volatile PanPlayerFactory panPlayers = MISSING;
    private final AuditBuffer auditBuffer;
    private final TraceRegistry traces;
    private final HiddenCommands hiddenCommands;
    private final PaperAuditService audit;

    public PantheonRuntime(JavaPlugin plugin, BootstrapResult.Started started, MainThreadExecutor mainThread) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.started = Objects.requireNonNull(started, "started");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.pantheonResources = PaperResourceLister.of(PantheonPlugin.class);
        this.traces = new TraceRegistry(started.clock());
        this.hiddenCommands = new HiddenCommands(new PaperCommandResolver(plugin.getServer()));
        this.auditBuffer = new AuditBuffer(started.backend(), started.scheduler(), started.clock(), started.log());
        this.audit = new PaperAuditService(auditBuffer, traces, hiddenCommands, started.backend(), started.clock());
    }

    /** The node's one audit buffer (audit §7). */
    public AuditBuffer auditBuffer() {
        return auditBuffer;
    }

    /** The active traces of the players on this node (audit §4). */
    public TraceRegistry traces() {
        return traces;
    }

    /** The commands never logged on this node (audit §6). */
    public HiddenCommands hiddenCommands() {
        return hiddenCommands;
    }

    /** The audit API of this node, shared by every plugin. */
    public PaperAuditService audit() {
        return audit;
    }

    /** The Pantheon plugin itself. */
    public JavaPlugin plugin() {
        return plugin;
    }

    public BootstrapResult.Started started() {
        return started;
    }

    public MainThreadExecutor mainThread() {
        return mainThread;
    }

    /** Where {@code lang/pantheon_<locale>.yml} comes from; part of every plugin's language. */
    public ResourceLister pantheonResources() {
        return pantheonResources;
    }

    /** The hooks of a new plugin language; broadcasts reach every player and the console. */
    public LanguageHooks languageHooks() {
        return new LanguageHooks().broadcastTarget(plugin::getServer)
                .playerLookup(audience -> audience instanceof Player p
                        ? Optional.of(panPlayers().local(p)) : Optional.empty());
    }

    public PanPlayerFactory panPlayers() {
        return panPlayers;
    }

    /** P-24/P-25: the factory of PanPlayers. */
    public void panPlayers(PanPlayerFactory factory) {
        this.panPlayers = Objects.requireNonNull(factory, "factory");
    }

    /** The players of this server (P-24: from their join to their quit). */
    public LocalPlayers localPlayers() {
        return localPlayers;
    }

    /** The PanPlayer of a player on this server, for {@code onChange} listeners; empty if not online here. */
    public Optional<LocalPanPlayer> localPlayer(UUID uuid) {
        return localPlayers.get(uuid).map(p -> p);
    }

    /** Shutdown.run: flush and the last audit send in parallel (R14), then the backend closes. */
    @Override
    public void close() {
        started.close(auditBuffer);
    }
}
