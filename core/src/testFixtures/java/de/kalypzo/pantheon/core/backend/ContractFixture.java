package de.kalypzo.pantheon.core.backend;

import java.util.List;
import java.util.UUID;

/**
 * One network under test for the contract suites: the in-memory network or a real Agora. Node ids used by the suites
 * are {@code lobby-01}, {@code lobby-02} (PAPER) and {@code proxy-01} (the proxy that presence and deliveries run
 * through). The network must have no warm-up delay for PAPER nodes (E11, {@code paperWelcomeDelay} = 0). {@link #node(String)} returns a started, connected node; asking twice for the same id gives the same node.
 */
public interface ContractFixture extends AutoCloseable {

    Backend node(String id);

    /** The proxy claims the player (server may be {@code null} until the first switch). */
    void join(PresenceEntry entry);

    void switchServer(UUID player, String server);

    void leave(UUID player);

    /** What the proxy {@code proxyId} received to deliver, in order. */
    List<Delivered> deliveries(String proxyId);

    /** The service goes away: every node is disconnected until {@link #restore()}. */
    void outage();

    /** The service is back; nodes reconnect on their own. */
    void restore();

    @Override
    void close();
}
