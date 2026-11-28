package de.kalypzo.pantheon.core.backend.agora;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.data.DocWriter;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.outbox.Outbox;
import de.kalypzo.pantheon.core.player.PlayerDataEngine;
import de.kalypzo.pantheon.data.Doc;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;

/** The write pipeline and player data of one node, wired like in the plugin (PlayerDataEngineTest's Node). */
final class Engine implements AutoCloseable {

    final DocWriter writer;
    final PlayerDataEngine players;
    private final Outbox outbox;
    private final Path dir;

    Engine(Backend backend) {
        try {
            dir = Files.createTempDirectory("bench-outbox");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Logger log = Logger.getLogger("bench");
        outbox = Outbox.open(dir.resolve("outbox.db"), Clock.systemUTC(), log);
        writer = new DocWriter(backend, outbox, Clock.systemUTC(), Runnable::run, log);
        players = new PlayerDataEngine(backend, writer, Runnable::run, log);
    }

    /** What {@code player.data()} returns after the login. */
    Doc load(UUID player) {
        return Bench.get(players.load(player, "bench", Duration.ofSeconds(10)));
    }

    /** Until every write of the player is confirmed by the database. */
    void awaitIdle(UUID player) {
        PlayerDoc ref = new PlayerDoc(player);
        while (!writer.idle(ref)) LockSupport.parkNanos(10_000);
    }

    @Override
    public void close() {
        Bench.get(writer.flush(Duration.ofSeconds(10)));
        players.close();
        outbox.close();
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) Files.deleteIfExists(p);
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // temp dir
        }
    }
}
