package de.kalypzo.pantheon.core.backend;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The proxy step of a teleport: connects {@code player} to {@code server}. */
@FunctionalInterface
public interface ProxyConnector {
    CompletableFuture<ConnectStatus> connect(UUID player, String server);
}
