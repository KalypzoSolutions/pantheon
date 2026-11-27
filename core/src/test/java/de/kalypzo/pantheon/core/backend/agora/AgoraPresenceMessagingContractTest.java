package de.kalypzo.pantheon.core.backend.agora;

import de.kalypzo.pantheon.core.backend.ContractFixture;
import de.kalypzo.pantheon.core.backend.PresenceMessagingContract;
import org.junit.jupiter.api.Tag;

/** The presence and messaging contract against a real Agora, the proxy simulated as a VELOCITY node. */
@Tag("integration")
class AgoraPresenceMessagingContractTest extends PresenceMessagingContract {

    @Override
    protected ContractFixture fixture() {
        return new AgoraContractFixture();
    }
}
