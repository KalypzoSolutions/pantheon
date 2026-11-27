package de.kalypzo.pantheon.core.backend;

import de.kalypzo.pantheon.core.audit.AuditBackend;
import de.kalypzo.pantheon.data.Subscription;
import java.util.function.Consumer;

/** All ports over one connection: data, presence, messages and audit. */
public interface Backend extends DataBackend, PresenceBackend, MessageBackend, AuditBackend, AutoCloseable {

    /** Starts the connection; {@code state} is what the node reports on every (re)connect. */
    void start(NodeState state);

    ConnectionState state();

    Subscription onConnectionState(Consumer<ConnectionState> listener);

    @Override
    void close();
}
