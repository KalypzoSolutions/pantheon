package de.kalypzo.pantheon.core.backend;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * One network under test for the contract suites: the in-memory network or a real Agora. Node ids used by the suites
 * are {@code lobby-01}, {@code lobby-02} (PAPER) and {@code proxy-01} (the proxy that presence and deliveries run
 * through). The network must have no warm-up delay for PAPER nodes (E11, {@code paperWelcomeDelay} = 0). {@link #node(String)} returns a started, connected node; asking twice for the same id gives the same node.
 */
public interface ContractFixture extends AutoCloseable {

    /** Teleport limits every fixture sets its network to ({@code PrepareArrival}, connect, arrival, whole). */
    Duration TELEPORT_PREPARE = Duration.ofMillis(500);
    Duration TELEPORT_CONNECT = Duration.ofMillis(1500);
    Duration TELEPORT_ARRIVAL = Duration.ofMillis(2500);
    Duration TELEPORT_TOTAL = Duration.ofMillis(5000);

    Backend node(String id);

    /** The proxy claims the player (server may be {@code null} until the first switch). */
    void join(PresenceEntry entry);

    void switchServer(UUID player, String server);

    void leave(UUID player);

    /** What the proxy {@code proxyId} received to deliver, in order. */
    List<Delivered> deliveries(String proxyId);

    /** The proxy answers {@code ConnectPlayer} with {@code connector}; without one it does not answer. */
    void proxyConnects(ProxyConnector connector);

    /** The connection of node {@code nodeId} ends (the node is gone for the network). */
    void disconnect(String nodeId);

    /** The service goes away: every node is disconnected until {@link #restore()}. */
    void outage();

    /** The service is back; nodes reconnect on their own. */
    void restore();

    @Override
    void close();
}
