package de.kalypzo.pantheon.core.backend.agora;

import de.kalypzo.pantheon.core.backend.ContractFixture;
import de.kalypzo.pantheon.core.backend.TeleportContract;
import org.junit.jupiter.api.Tag;

/** The teleport contract against a real Agora, the proxy simulated as a VELOCITY node. */
@Tag("integration")
class AgoraTeleportContractTest extends TeleportContract {

    @Override
    protected ContractFixture fixture() {
        return new AgoraContractFixture();
    }
}
