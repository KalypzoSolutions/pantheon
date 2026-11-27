package de.kalypzo.pantheon.agora;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeConfigTest {

    @Test
    void readsAllVariables() throws Exception {
        NodeConfig c = NodeConfig.fromEnv(Map.of(
                "PANTHEON_AGORA_ADDRESS", "localhost:9090",
                "PANTHEON_NODE_ID", "lobby-01",
                "PANTHEON_AGORA_TOKEN", "s3cret"));
        assertThat(c.address()).isEqualTo("localhost:9090");
        assertThat(c.nodeId()).isEqualTo("lobby-01");
    }

    @Test
    void missingTokenNamesVariable() {
        assertThatThrownBy(() -> NodeConfig.fromEnv(Map.of(
                "PANTHEON_AGORA_ADDRESS", "localhost:9090",
                "PANTHEON_NODE_ID", "lobby-01")))
                .isInstanceOf(NodeConfigException.class)
                .hasMessageContaining("PANTHEON_AGORA_TOKEN");
    }

    @Test
    void missingVariableMessageNeverContainsValues() {
        assertThatThrownBy(() -> NodeConfig.fromEnv(Map.of(
                "PANTHEON_AGORA_ADDRESS", "localhost:9090",
                "PANTHEON_AGORA_TOKEN", "s3cret-value")))
                .isInstanceOf(NodeConfigException.class)
                .hasMessageContaining("PANTHEON_NODE_ID")
                .hasMessageNotContaining("s3cret-value");
    }

    @Test
    void blankValueCountsAsMissing() {
        assertThatThrownBy(() -> NodeConfig.fromEnv(Map.of(
                "PANTHEON_AGORA_ADDRESS", " ",
                "PANTHEON_NODE_ID", "n",
                "PANTHEON_AGORA_TOKEN", "t")))
                .hasMessageContaining("PANTHEON_AGORA_ADDRESS");
    }

    @Test
    void tokenNeverInToString() throws Exception {
        NodeConfig c = NodeConfig.fromEnv(Map.of(
                "PANTHEON_AGORA_ADDRESS", "localhost:9090",
                "PANTHEON_NODE_ID", "lobby-01",
                "PANTHEON_AGORA_TOKEN", "s3cret"));
        assertThat(c.toString())
                .isEqualTo("NodeConfig[address=localhost:9090, nodeId=lobby-01, token=***]")
                .doesNotContain("s3cret");
    }
}
