package de.kalypzo.pantheon.paper;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

/**
 * The server main thread as an {@link Executor} ({@code Pantheon.mainThread()}): on the main thread a task runs at
 * once, otherwise it is scheduled for the next tick. {@link #queued()} always schedules; listeners (E15) use it, since
 * the core hands them over while it holds a document's lock and expects them never to run inline.
 */
public final class MainThreadExecutor implements Executor {

    private final Plugin plugin;
    private final Executor queued;

    public MainThreadExecutor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.queued = this::schedule;
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            schedule(task);
        }
    }

    /** Runs every task on a later tick, in submission order, also when called on the main thread. */
    public Executor queued() {
        return queued;
    }

    private void schedule(Runnable task) {
        Objects.requireNonNull(task, "task");
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException e) { // Pantheon is disabled: the server is stopping
            plugin.getLogger().log(Level.FINE, "Main thread task dropped, Pantheon is disabled", e);
        }
    }
}
