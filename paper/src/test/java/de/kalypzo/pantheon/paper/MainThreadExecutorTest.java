package de.kalypzo.pantheon.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class MainThreadExecutorTest {

    private ServerMock server;
    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("Pantheon");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void runsInlineOnMainThread() {
        List<String> ran = new CopyOnWriteArrayList<>();
        new MainThreadExecutor(plugin).execute(() -> ran.add(Thread.currentThread().getName()));
        assertThat(ran).containsExactly(Thread.currentThread().getName());
    }

    @Test
    void schedulesFromOtherThreads() throws Exception {
        List<Boolean> ran = new CopyOnWriteArrayList<>();
        MainThreadExecutor main = new MainThreadExecutor(plugin);
        Thread t = new Thread(() -> main.execute(() -> ran.add(server.isPrimaryThread())));
        t.start();
        t.join();
        assertThat(ran).isEmpty();
        server.getScheduler().performOneTick();
        assertThat(ran).containsExactly(true);
    }

    @Test
    void queuedAlwaysSchedulesInOrder() {
        List<Integer> ran = new CopyOnWriteArrayList<>();
        MainThreadExecutor main = new MainThreadExecutor(plugin);
        main.queued().execute(() -> ran.add(1));
        main.queued().execute(() -> ran.add(2));
        assertThat(ran).isEmpty(); // never inline, also on the main thread (listeners, E15)
        server.getScheduler().performOneTick();
        assertThat(ran).containsExactly(1, 2);
    }
}
