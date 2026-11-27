package de.kalypzo.pantheon.core.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BackendFactoryTest {

    @Test
    void memoryNeedsNoAgoraEnvAndUsesNodeIdLocal() {
        try (Backend b = BackendFactory.create(Map.of("PANTHEON_BACKEND", "memory"), Path.of("."))) {
            assertThat(b).isInstanceOf(InMemoryNetwork.Node.class);
            assertThat(((InMemoryNetwork.Node) b).nodeId()).isEqualTo("local");
            assertThat(((InMemoryNetwork.Node) b).network()).isNotNull();
            assertThat(b.state()).isEqualTo(ConnectionState.CONNECTED);
        }
    }

    @Test
    void memoryUsesConfiguredNodeId() {
        try (Backend b = BackendFactory.create(
                Map.of("PANTHEON_BACKEND", "memory", "PANTHEON_NODE_ID", "lobby-01"), Path.of("."))) {
            assertThat(((InMemoryNetwork.Node) b).nodeId()).isEqualTo("lobby-01");
        }
    }

    @Test
    void agoraOrEmptyNeedsAgoraEnv() {
        assertThatThrownBy(() -> BackendFactory.create(Map.of(), Path.of(".")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PANTHEON_AGORA_ADDRESS");
        assertThatThrownBy(() -> BackendFactory.create(Map.of("PANTHEON_BACKEND", "agora"), Path.of(".")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownBackendRejected() {
        assertThatThrownBy(() -> BackendFactory.create(Map.of("PANTHEON_BACKEND", "redis"), Path.of(".")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
