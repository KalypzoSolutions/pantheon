package de.kalypzo.pantheon.core.backend;

import de.kalypzo.pantheon.core.data.wire.StoreRef;
import java.util.Collection;
import java.util.UUID;

/** What a node holds; sent on every (re)connect and heartbeat. */
public interface NodeState {

    Collection<UUID> heldPlayers();

    Collection<StoreRef> watchedStores();
}
