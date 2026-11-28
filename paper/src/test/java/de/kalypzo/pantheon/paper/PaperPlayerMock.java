package de.kalypzo.pantheon.paper;

import io.papermc.paper.entity.TeleportFlag;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** MockBukkit does not implement {@code teleportAsync}; this emulates Paper, which rejects non-finite coordinates. */
class PaperPlayerMock extends PlayerMock {

    PaperPlayerMock(ServerMock server, String name) {
        super(server, name);
    }

    PaperPlayerMock(ServerMock server, String name, UUID uuid) {
        super(server, name, uuid);
    }

    @Override
    public CompletableFuture<Boolean> teleportAsync(Location l, PlayerTeleportEvent.TeleportCause cause,
                                                    TeleportFlag... flags) {
        if (!Double.isFinite(l.getX()) || !Double.isFinite(l.getY()) || !Double.isFinite(l.getZ())) {
            throw new IllegalArgumentException("coordinates not finite");
        }
        return CompletableFuture.completedFuture(teleport(l));
    }
}
