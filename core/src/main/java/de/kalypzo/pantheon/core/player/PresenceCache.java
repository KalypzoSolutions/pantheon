package de.kalypzo.pantheon.core.player;

import de.kalypzo.pantheon.core.backend.PresenceBackend;
import de.kalypzo.pantheon.core.backend.PresenceChange;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.data.Subscription;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Node-local copy of the network presence (player.md §3, §5). Fed by the backend snapshot and presence events.
 * Reads are safe from any thread and never block; writes are serialized. A {@code Replaced} (Welcome, or restore after
 * an outage) swaps the whole content; until one arrives the old copy stays valid (E11).
 */
public final class PresenceCache {

    private static final Logger LOG = Logger.getLogger(PresenceCache.class.getName());

    /** Immutable view: entries plus lower-cased name index. Swapped atomically, so readers never see a mix. */
    private record State(Map<UUID, PresenceEntry> entries, Map<String, UUID> names) {
        static final State EMPTY = new State(Map.of(), Map.of());
    }

    private volatile State state = State.EMPTY;
    private final List<Consumer<PresenceChange>> listeners = new CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();

    public PresenceCache(PresenceBackend backend) {
        Subscription sub = backend.onPresenceChanged(this::apply);
        try {
            synchronized (writeLock) {
                state = build(backend.snapshot());
            }
        } catch (RuntimeException | Error e) {
            sub.unsubscribe();
            throw e;
        }
    }

    public Optional<PresenceEntry> get(UUID player) {
        return Optional.ofNullable(state.entries().get(player));
    }

    public Optional<PresenceEntry> byName(String name) {
        State st = state;
        UUID id = st.names().get(key(name));
        return id == null ? Optional.empty() : Optional.ofNullable(st.entries().get(id));
    }

    public Collection<PresenceEntry> all() {
        return state.entries().values();
    }

    public Subscription onChange(Consumer<PresenceChange> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Changes are applied and announced under one lock, so listeners see them in the order they were applied, on the
     * thread that delivers the change. Listeners must be quick; reads on the cache never block.
     */
    private void apply(PresenceChange change) {
        synchronized (writeLock) {
            State old = state;
            state = switch (change) {
                case PresenceChange.Replaced r -> build(r.all());
                case PresenceChange.Upsert u -> {
                    Map<UUID, PresenceEntry> e = new HashMap<>(old.entries());
                    e.put(u.e().player(), u.e());
                    yield new State(Collections.unmodifiableMap(e), index(e.values()));
                }
                case PresenceChange.Removed r -> {
                    Map<UUID, PresenceEntry> e = new HashMap<>(old.entries());
                    e.remove(r.player());
                    yield new State(Collections.unmodifiableMap(e), index(e.values()));
                }
            };
            for (Consumer<PresenceChange> l : listeners) {
                try {
                    l.accept(change);
                } catch (RuntimeException ex) {
                    LOG.log(Level.WARNING, "Presence listener failed", ex);
                }
            }
        }
    }

    private static State build(Collection<PresenceEntry> all) {
        Map<UUID, PresenceEntry> e = new HashMap<>();
        for (PresenceEntry p : all) e.put(p.player(), p);
        return new State(Collections.unmodifiableMap(e), index(e.values()));
    }

    private static Map<String, UUID> index(Collection<PresenceEntry> all) {
        Map<String, UUID> n = new HashMap<>();
        for (PresenceEntry p : all) n.put(key(p.name()), p.player());
        return Collections.unmodifiableMap(n);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
