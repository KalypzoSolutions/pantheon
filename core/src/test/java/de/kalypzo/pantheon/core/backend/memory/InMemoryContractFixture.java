package de.kalypzo.pantheon.core.backend.memory;

import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.backend.ContractFixture;
import de.kalypzo.pantheon.core.backend.Delivered;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.backend.ProxyConnector;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** The contract fixture over a fresh {@link InMemoryNetwork}. */
final class InMemoryContractFixture implements ContractFixture {

    private final InMemoryNetwork network = new InMemoryNetwork(Clock.systemUTC(),
            new TeleportTimeouts(TELEPORT_PREPARE, TELEPORT_CONNECT, TELEPORT_ARRIVAL, TELEPORT_TOTAL));

    @Override
    public Backend node(String id) {
        return network.node(id);
    }

    @Override
    public void join(PresenceEntry entry) {
        network.join(entry);
    }

    @Override
    public void switchServer(UUID player, String server) {
        network.switchServer(player, server);
    }

    @Override
    public void leave(UUID player) {
        network.leave(player);
    }

    @Override
    public List<Delivered> deliveries(String proxyId) {
        return network.deliveries(proxyId);
    }

    @Override
    public void proxyConnects(ProxyConnector connector) {
        network.proxyConnects(connector);
    }

    @Override
    public void disconnect(String nodeId) {
        network.outage(nodeId);
    }

    @Override
    public void outage() {
        network.outage();
    }

    @Override
    public void restore() {
        network.restore();
    }

    @Override
    public void close() {
        network.close();
    }
}
