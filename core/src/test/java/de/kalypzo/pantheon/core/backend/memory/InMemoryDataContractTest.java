package de.kalypzo.pantheon.core.backend.memory;

import de.kalypzo.pantheon.core.backend.ContractFixture;
import de.kalypzo.pantheon.core.backend.DataBackendContract;

class InMemoryDataContractTest extends DataBackendContract {

    @Override
    protected ContractFixture fixture() {
        return new InMemoryContractFixture();
    }
}
