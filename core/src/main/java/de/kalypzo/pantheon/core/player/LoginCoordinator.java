package de.kalypzo.pantheon.core.player;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The life cycle of a player's data on this node (player.md §7, §9, E25):
 * <ol>
 *   <li>{@link #preLogin}: loads all namespaces (this node becomes holder, {@code pantheon.name} is written) within
 *       the login timeout. A failure is a {@link LoginOutcome.Denied}, never empty data.
 *   <li>After an allowed pre-login the player must join within the join grace (30 s): {@link #joined} stops the
 *       timer, otherwise the player is released. {@link #loginAborted} (the login was refused after the pre-login)
 *       releases at once.
 *   <li>{@link #quit} releases: the profile leaves memory, writes still running finish in the background.
 * </ol>
 * Two connections of the same player on this node (the new one kicks the old one) share the data: a quit while a
 * newer pre-login waits for its join does not release, and a refused or timed-out newer login does not release while
 * the old connection is still joined. Thread-safe.
 */
public final class LoginCoordinator {

    public static final Duration DEFAULT_JOIN_GRACE = Duration.ofSeconds(30);

    private final PlayerDataEngine engine;
    private final ScheduledExecutorService scheduler;
    private final Duration loginTimeout;
    private final Duration joinGrace;

    private final Object lock = new Object();
    /** Allowed pre-logins waiting for their join: the release timer. Written under {@code lock}. */
    private final Map<UUID, ScheduledFuture<?>> awaitingJoin = new ConcurrentHashMap<>();
    /** Players that joined and have not quit. Written under {@code lock}. */
    private final Set<UUID> joined = ConcurrentHashMap.newKeySet();

    public LoginCoordinator(PlayerDataEngine engine, ScheduledExecutorService scheduler, Duration loginTimeout,
                            Duration joinGrace) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.loginTimeout = Objects.requireNonNull(loginTimeout, "loginTimeout");
        this.joinGrace = Objects.requireNonNull(joinGrace, "joinGrace");
    }

    /** The login timeout the loads use; callers wait at most this plus a second (R29). */
    public Duration loginTimeout() {
        return loginTimeout;
    }

    /** Loads the player's data; completes within the login timeout and never exceptionally. */
    public CompletableFuture<LoginOutcome> preLogin(UUID player, String name) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(name, "name");
        return engine.load(player, name, loginTimeout).handle((doc, e) -> e == null
                ? allowed(player, doc)
                : new LoginOutcome.Denied(unwrap(e)));
    }

    /**
     * The login was refused after the pre-login (another plugin, a full server): release at once (E25). A player who
     * is still joined from an earlier connection keeps the data.
     */
    public void loginAborted(UUID player) {
        Objects.requireNonNull(player, "player");
        synchronized (lock) {
            cancelTimer(player);
            if (!joined.contains(player)) engine.release(player);
        }
    }

    /** The player joined: the data stays until {@link #quit}. */
    public void joined(UUID player) {
        Objects.requireNonNull(player, "player");
        synchronized (lock) {
            cancelTimer(player);
            joined.add(player);
        }
    }

    /** The player left this server: session over, the profile leaves memory, running writes finish. */
    public void quit(UUID player) {
        Objects.requireNonNull(player, "player");
        synchronized (lock) {
            joined.remove(player);
            if (awaitingJoin.containsKey(player)) return; // a newer login of the player holds the data now
            engine.release(player);
        }
    }

    private LoginOutcome allowed(UUID player, de.kalypzo.pantheon.data.Doc doc) {
        synchronized (lock) {
            cancelTimer(player); // a newer pre-login of the same player starts its own grace
            AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
            ScheduledFuture<?> timer = scheduler.schedule(() -> noJoin(player, self.get()),
                    joinGrace.toMillis(), TimeUnit.MILLISECONDS);
            self.set(timer);
            awaitingJoin.put(player, timer);
        }
        return new LoginOutcome.Allowed(doc);
    }

    /** E25: no join within the grace, e.g. the client disconnected before joining. */
    private void noJoin(UUID player, ScheduledFuture<?> timer) {
        synchronized (lock) { // the timer is set before the lock is released, so it is never null here
            if (timer != null && awaitingJoin.remove(player, timer) && !joined.contains(player)) {
                engine.release(player);
            }
        }
    }

    /** Callers hold {@code lock}. */
    private void cancelTimer(UUID player) {
        ScheduledFuture<?> timer = awaitingJoin.remove(player);
        if (timer != null) timer.cancel(false);
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
