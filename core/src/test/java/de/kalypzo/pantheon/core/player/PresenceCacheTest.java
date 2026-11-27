package de.kalypzo.pantheon.core.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kalypzo.pantheon.core.backend.LocationResolver;
import de.kalypzo.pantheon.core.backend.PresenceBackend;
import de.kalypzo.pantheon.core.backend.PresenceChange;
import de.kalypzo.pantheon.data.Subscription;
import de.kalypzo.pantheon.world.NetworkLocation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class PresenceCacheTest {

    private static PresenceEntry entry(UUID id, String name, String server) {
        return new PresenceEntry(id, name, server, "proxy-1", Locale.GERMANY, Instant.EPOCH);
    }

    @Test
    void replaceOnWelcome() {
        try (InMemoryNetwork net = new InMemoryNetwork()) {
            InMemoryNetwork.Node node = net.node("lobby-01");
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            net.join(entry(a, "Alice", "lobby-01"));
            PresenceCache cache = new PresenceCache(node);
            assertThat(cache.all()).extracting(PresenceEntry::player).containsExactly(a);

            net.outage();
            net.leave(a);
            net.join(entry(b, "Bob", "lobby-01"));
            net.restore();

            Awaitility.await().untilAsserted(() -> assertThat(cache.get(b)).isPresent());
            assertThat(cache.get(a)).isEmpty();
            assertThat(cache.byName("alice")).isEmpty();
            assertThat(cache.get(b)).isPresent();
            assertThat(cache.all()).hasSize(1);
        }
    }

    @Test
    void nameLookupIgnoresCase() {
        try (InMemoryNetwork net = new InMemoryNetwork()) {
            PresenceCache cache = new PresenceCache(net.node("lobby-01"));
            UUID a = UUID.randomUUID();
            net.join(entry(a, "AliceX", "lobby-01"));
            Awaitility.await().untilAsserted(() -> assertThat(cache.get(a)).isPresent());
            assertThat(cache.byName("alicex")).map(PresenceEntry::player).contains(a);
            assertThat(cache.byName("ALICEX")).isPresent();
            assertThat(cache.byName("other")).isEmpty();
        }
    }

    @Test
    void renameUpdatesNameIndex() {
        try (InMemoryNetwork net = new InMemoryNetwork()) {
            PresenceCache cache = new PresenceCache(net.node("lobby-01"));
            UUID a = UUID.randomUUID();
            net.join(entry(a, "Old", "lobby-01"));
            net.join(entry(a, "New", "lobby-01"));
            Awaitility.await().untilAsserted(() -> assertThat(cache.get(a)).map(PresenceEntry::name).contains("New"));
            assertThat(cache.byName("old")).isEmpty();
            assertThat(cache.byName("new")).map(PresenceEntry::player).contains(a);
        }
    }

    @Test
    void removedPlayerNotFoundByName() {
        try (InMemoryNetwork net = new InMemoryNetwork()) {
            PresenceCache cache = new PresenceCache(net.node("lobby-01"));
            UUID a = UUID.randomUUID();
            net.join(entry(a, "Alice", "lobby-01"));
            Awaitility.await().untilAsserted(() -> assertThat(cache.get(a)).isPresent());
            List<PresenceChange> seen = new CopyOnWriteArrayList<>();
            cache.onChange(seen::add);
            net.leave(a);
            Awaitility.await().untilAsserted(() -> assertThat(seen).hasSize(1));
            assertThat(cache.byName("alice")).isEmpty();
            assertThat(cache.get(a)).isEmpty();
            assertThat(seen).containsExactly(new PresenceChange.Removed(a));
        }
    }

    @Test
    void concurrentReadsDuringUpdates() throws Exception {
        try (InMemoryNetwork net = new InMemoryNetwork()) {
            PresenceCache cache = new PresenceCache(net.node("lobby-01"));
            UUID id = UUID.randomUUID();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        cache.byName("player-5");
                        cache.get(id);
                        cache.all().size();
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            reader.start();
            for (int i = 0; i < 10_000; i++) {
                net.join(entry(i % 10 == 0 ? id : UUID.randomUUID(), "Player-" + (i % 50), "lobby-01"));
                if (i % 3 == 0) net.leave(id);
            }
            net.join(entry(id, "Final", "lobby-01"));
            Awaitility.await().untilAsserted(() -> assertThat(cache.byName("final")).isPresent());
            reader.interrupt();
            reader.join(5000);
            assertThat(failure.get()).isNull();
        }
    }

    @Test
    void replacedIsNeverObservedAsMixedState() throws Exception {
        List<Consumer<PresenceChange>> hook = new CopyOnWriteArrayList<>();
        List<PresenceEntry> setA = new ArrayList<>();
        List<PresenceEntry> setB = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            setA.add(entry(UUID.nameUUIDFromBytes(("a" + i).getBytes()), "A" + i, "s"));
            setB.add(entry(UUID.nameUUIDFromBytes(("b" + i).getBytes()), "B" + i, "s"));
        }
        PresenceBackend backend = new PresenceBackend() {
            public Collection<PresenceEntry> snapshot() { return setA; }
            public Subscription onPresenceChanged(Consumer<PresenceChange> l) { hook.add(l); return () -> hook.remove(l); }
            public CompletableFuture<NetworkLocation> location(UUID p) { throw new UnsupportedOperationException(); }
            public void answerLocations(LocationResolver r) { }
        };
        PresenceCache cache = new PresenceCache(backend);
        Set<UUID> idsA = new HashSet<>();
        setA.forEach(e -> idsA.add(e.player()));
        Set<UUID> idsB = new HashSet<>();
        setB.forEach(e -> idsB.add(e.player()));
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                Set<UUID> seen = new HashSet<>();
                cache.all().forEach(e -> seen.add(e.player()));
                if (!seen.equals(idsA) && !seen.equals(idsB)) {
                    failure.set("mixed state: " + seen.size() + " entries");
                    return;
                }
            }
        });
        reader.start();
        for (int i = 0; i < 20_000; i++) {
            hook.get(0).accept(new PresenceChange.Replaced(i % 2 == 0 ? setB : setA));
        }
        stop.set(true);
        reader.join(5000);
        assertThat(failure.get()).isNull();
    }

    @Test
    void failingSnapshotUnregistersSubscription() {
        AtomicBoolean unsubscribed = new AtomicBoolean();
        PresenceBackend backend = new PresenceBackend() {
            public Collection<PresenceEntry> snapshot() { throw new IllegalStateException("boom"); }
            public Subscription onPresenceChanged(Consumer<PresenceChange> l) { return () -> unsubscribed.set(true); }
            public CompletableFuture<NetworkLocation> location(UUID p) { throw new UnsupportedOperationException(); }
            public void answerLocations(LocationResolver r) { }
        };
        assertThatThrownBy(() -> new PresenceCache(backend)).isInstanceOf(IllegalStateException.class);
        assertThat(unsubscribed).isTrue();
    }
}
