package de.kalypzo.pantheon.core.backend.agora;

import de.kalypzo.pantheon.core.backend.AuditBackendContract;
import de.kalypzo.pantheon.core.backend.ContractFixture;
import org.junit.jupiter.api.Tag;

/** The audit contract against a real Agora; session entries come from the simulated VELOCITY proxy. */
@Tag("integration")
class AgoraAuditContractTest extends AuditBackendContract {

    @Override
    protected ContractFixture fixture() {
        return new AgoraContractFixture();
    }
}
