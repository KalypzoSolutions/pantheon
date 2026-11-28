package de.kalypzo.pantheon.paper;

import de.kalypzo.pantheon.core.player.LoginCoordinator;
import de.kalypzo.pantheon.paper.audit.AuditListener;
import de.kalypzo.pantheon.paper.audit.PaperCommandResolver;
import de.kalypzo.pantheon.core.runtime.Bootstrap;
import de.kalypzo.pantheon.core.runtime.BootstrapResult;
import de.kalypzo.pantheon.core.runtime.PantheonRegistry;
import de.kalypzo.pantheon.language.CommonMessage;
import de.kalypzo.pantheon.language.Language;
import de.kalypzo.pantheon.spi.PantheonProvider;
import java.time.Clock;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * The Pantheon plugin (P-23). {@code onLoad} registers the {@link PantheonProvider}, {@code onEnable} runs the
 * {@link Bootstrap} (waits at most 5 s for the first connection outcome, E12). A configuration error (missing
 * variable, token rejected) only disables Pantheon: it is logged without the token, the provider is unregistered
 * first (R31), and the server keeps running (megalodon §1: a library never shuts the server down). The
 * {@code PluginDisableEvent} of a consumer tears down its instance. Not final: MockBukkit subclasses plugins.
 */
public class PantheonPlugin extends JavaPlugin implements Listener {

    /** Where the environment comes from; tests replace it. */
    static volatile Supplier<Map<String, String>> environment = System::getenv;
    /** The message of the last configuration error, for tests. */
    static volatile @Nullable String lastStartError;

    private final PantheonRegistry<JavaPlugin, PantheonImpl> registry = new PantheonRegistry<>();
    private volatile @Nullable PantheonRuntime runtime;

    @Override
    public void onLoad() {
        PantheonProvider.register(plugin -> {
            PantheonRuntime rt = runtime;
            if (rt == null) {
                throw new IllegalStateException("Pantheon is not enabled (configuration error or not started yet)");
            }
            // R51: a disabled plugin keeps its instance until the teardown, but never gets a new one
            return registry.get(plugin, plugin.isEnabled(), p -> new PantheonImpl(p, rt));
        });
    }

    @Override
    public void onEnable() {
        MainThreadExecutor mainThread = new MainThreadExecutor(this);
        BootstrapResult result = Bootstrap.start(environment.get(), getDataFolder().toPath(),
                Bootstrap.FIRST_CONNECT_WAIT, mainThread.queued(), getLogger());
        switch (result) {
            case BootstrapResult.ConfigError error -> {
                lastStartError = error.message();
                getLogger().severe("Pantheon is not starting: " + error.message());
                PantheonProvider.unregister(); // R31: before disabling, so no plugin gets a half-started instance
                getServer().getPluginManager().disablePlugin(this); // never Bukkit.shutdown()
            }
            case BootstrapResult.Started started -> {
                lastStartError = null;
                PantheonRuntime rt = new PantheonRuntime(this, started, mainThread);
                runtime = rt;
                getServer().getPluginManager().registerEvents(this, this);
                registerLifecycle(rt, started, mainThread);
                getLogger().info("Pantheon started as node '" + started.nodeId() + "'");
            }
        }
    }

    @Override
    public void onDisable() {
        PantheonRuntime rt = runtime;
        runtime = null; // first: no new instance after closeAll
        registry.closeAll(); // retired instances whose teardown tick has not come yet included
        // P-26: Shutdown.run (flush <= 10 s and, in parallel, the last audit send <= 2 s (R14); rest in the outbox,
        // close backend and outbox, unregister); never a server stop
        if (rt != null) rt.close();
        else PantheonProvider.unregister();
    }

    /**
     * A consumer is being disabled. Paper fires this event before the plugin's {@code onDisable}, so the instance is
     * only retired here (still handed out, cached stores still loaded) and torn down on the next tick, after its
     * {@code onDisable} (R51): its listeners and cached stores go, its running writes finish. If Pantheon is disabled
     * before that tick, its {@code onDisable} tears everything down at once.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!(event.getPlugin() instanceof JavaPlugin plugin) || plugin == this) return;
        if (!registry.retire(plugin)) return;
        try {
            getServer().getScheduler().runTask(this, () -> registry.close(plugin));
        } catch (IllegalPluginAccessException e) { // Pantheon is disabled already
            registry.close(plugin);
        }
    }

    /** P-24: login, join and quit of players, location answers for players on this server (R10), and arrivals. */
    private void registerLifecycle(PantheonRuntime rt, BootstrapResult.Started started, MainThreadExecutor mainThread) {
        PantheonImpl ownPantheon = registry.get(this, true, p -> new PantheonImpl(p, rt));
        Language own = ownPantheon.language(); // Pantheon's own texts
        LoginCoordinator coordinator = new LoginCoordinator(started.players(), started.scheduler(),
                started.loginTimeout(), LoginCoordinator.DEFAULT_JOIN_GRACE);
        PlayerLifecycleListener lifecycle = new PlayerLifecycleListener(coordinator, started.players(),
                rt.localPlayers(), started.nodeId(), started.network(), mainThread, getServer().getPluginManager(),
                () -> own.message(CommonMessage.DATA_UNAVAILABLE).component(own.defaultLocale()), getLogger(),
                (self, player) -> ownPantheon.paperPlayers().network(player.getUniqueId(), player.getName()),
                rt.traces());
        rt.panPlayers(lifecycle.localFactory());
        started.backend().answerLocations(lifecycle::resolveLocation);
        getServer().getPluginManager().registerEvents(lifecycle, this);
        PaperArrivals arrivals = new PaperArrivals(getServer(), uuid -> rt.localPlayers().get(uuid).isPresent(),
                started.backend()::reportArrival, mainThread, Clock.systemUTC(), getLogger());
        started.backend().answerArrivals(arrivals); // network teleports to this server
        getServer().getPluginManager().registerEvents(arrivals, this);
        getServer().getPluginManager().registerEvents(new AuditListener(rt.audit(), rt.hiddenCommands(),
                new PaperCommandResolver(getServer()), getLogger()), this); // P-37
    }

    /** The shared runtime, or null while Pantheon is not enabled. */
    @Nullable PantheonRuntime runtime() {
        return runtime;
    }
}
