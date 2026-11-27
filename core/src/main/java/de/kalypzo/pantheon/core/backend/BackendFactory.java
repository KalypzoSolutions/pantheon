package de.kalypzo.pantheon.core.backend;

import de.kalypzo.pantheon.agora.NodeConfig;
import de.kalypzo.pantheon.agora.NodeConfigException;
import de.kalypzo.pantheon.core.backend.agora.AgoraBackend;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import java.nio.file.Path;
import java.util.Map;

/** Picks the backend from the environment (E12, R12). */
public final class BackendFactory {

    public static final String BACKEND = "PANTHEON_BACKEND";
    public static final String NODE_ID = "PANTHEON_NODE_ID";
    public static final String DEFAULT_NODE_ID = "local";

    private BackendFactory() {}

    /**
     * {@code PANTHEON_BACKEND=memory}: a node {@code PANTHEON_NODE_ID} (default {@code local}) of a new
     * {@link InMemoryNetwork}, reachable via {@link InMemoryNetwork.Node#network()}. {@code agora} or empty: an
     * {@link AgoraBackend} (not started) from {@code PANTHEON_AGORA_ADDRESS}, {@code PANTHEON_NODE_ID} and
     * {@code PANTHEON_AGORA_TOKEN}.
     *
     * @throws IllegalStateException if one of the Agora variables is missing (the message names it, never a value)
     */
    public static Backend create(Map<String, String> env, Path dataFolder) {
        String kind = env.getOrDefault(BACKEND, "").trim();
        return switch (kind) {
            case "memory" -> {
                String nodeId = env.getOrDefault(NODE_ID, "").trim();
                yield InMemoryNetwork.single(nodeId.isEmpty() ? DEFAULT_NODE_ID : nodeId);
            }
            case "", "agora" -> {
                NodeConfig config;
                try {
                    config = NodeConfig.fromEnv(env);
                } catch (NodeConfigException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
                yield new AgoraBackend(config);
            }
            default -> throw new IllegalArgumentException(
                    BACKEND + " must be 'agora' or 'memory', was '" + kind + "'");
        };
    }
}
