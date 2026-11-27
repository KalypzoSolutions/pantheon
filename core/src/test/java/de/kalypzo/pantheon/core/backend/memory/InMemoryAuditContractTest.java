package de.kalypzo.pantheon.core.backend.memory;

import de.kalypzo.pantheon.core.backend.AuditBackendContract;
import de.kalypzo.pantheon.core.backend.ContractFixture;

class InMemoryAuditContractTest extends AuditBackendContract {

    @Override
    protected ContractFixture fixture() {
        return new InMemoryContractFixture();
    }
}
