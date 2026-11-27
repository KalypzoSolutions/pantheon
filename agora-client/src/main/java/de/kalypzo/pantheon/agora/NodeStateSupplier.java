package de.kalypzo.pantheon.agora;

import de.kalypzo.agora.proto.v1.Heartbeat;
import de.kalypzo.agora.proto.v1.Hello;

/** Supplies the node's current state for {@code Hello} (each new stream) and periodic {@code Heartbeat}. */
public interface NodeStateSupplier {

    Hello hello();

    Heartbeat heartbeat();
}
