package de.kalypzo.pantheon.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** P-33. */
class TraceRegistryTest {

    private final TraceRegistry registry = new TraceRegistry();
    private final UUID player = UUID.randomUUID();

    @Test
    void beginEndsPrevious() {
        UUID first = registry.begin(player);
        UUID second = registry.begin(player);
        assertThat(second).isNotEqualTo(first);
        assertThat(registry.active(player)).contains(second);
        assertThat(registry.traceFor(player, "shop:buy", null)).isEqualTo(second);
    }

    @Test
    void endOfStaleTraceIsNoop() {
        UUID first = registry.begin(player);
        UUID second = registry.begin(player);
        registry.end(player, first);
        assertThat(registry.active(player)).contains(second);
        registry.end(player, second);
        assertThat(registry.active(player)).isEmpty();
    }

    @Test
    void pantheonActionsGetNoAutomaticTrace() {
        registry.begin(player);
        assertThat(registry.traceFor(player, "pantheon:chat", null)).isNull();
        UUID explicit = UUID.randomUUID();
        assertThat(registry.traceFor(player, "pantheon:chat", explicit)).isEqualTo(explicit);
    }

    @Test
    void explicitTraceWins() {
        registry.begin(player);
        UUID explicit = UUID.randomUUID();
        assertThat(registry.traceFor(player, "shop:buy", explicit)).isEqualTo(explicit);
        assertThat(registry.traceFor(UUID.randomUUID(), "shop:buy", null)).isNull();
    }

    @Test
    void endAllRemovesActive() {
        registry.begin(player);
        registry.endAll(player);
        assertThat(registry.active(player)).isEmpty();
        assertThat(registry.traceFor(player, "shop:buy", null)).isNull();
    }

    @Test
    void concurrentBeginAndEndLeavesConsistentState() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var ids = java.util.concurrent.ConcurrentHashMap.<UUID>newKeySet();
        var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
        for (int t = 0; t < 8; t++) {
            tasks.add(() -> {
                for (int i = 0; i < 500; i++) {
                    UUID id = registry.begin(player);
                    ids.add(id);
                    registry.end(player, id);
                }
                return null;
            });
        }
        for (var f : pool.invokeAll(tasks)) {
            f.get();
        }
        pool.shutdown();
        assertThat(ids).hasSize(4000);
        UUID last = registry.begin(player);
        assertThat(registry.active(player)).contains(last);
    }
}
