package de.kalypzo.pantheon.agora;

import java.util.Map;

/** Connection settings of a node. The token is never exposed publicly and never printed. */
public final class NodeConfig {

    public static final String ENV_ADDRESS = "PANTHEON_AGORA_ADDRESS";
    public static final String ENV_NODE_ID = "PANTHEON_NODE_ID";
    public static final String ENV_TOKEN = "PANTHEON_AGORA_TOKEN";

    private final String address;
    private final String nodeId;
    private final String token;

    private NodeConfig(String address, String nodeId, String token) {
        this.address = address;
        this.nodeId = nodeId;
        this.token = token;
    }

    public static NodeConfig fromEnv(Map<String, String> env) throws NodeConfigException {
        return new NodeConfig(require(env, ENV_ADDRESS), require(env, ENV_NODE_ID), require(env, ENV_TOKEN));
    }

    private static String require(Map<String, String> env, String name) throws NodeConfigException {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new NodeConfigException("Environment variable " + name + " is not set");
        }
        return value.strip();
    }

    public String address() {
        return address;
    }

    public String nodeId() {
        return nodeId;
    }

    /** Package-private on purpose: only the connection's interceptor needs it. */
    String token() {
        return token;
    }

    @Override
    public String toString() {
        return "NodeConfig[address=" + address + ", nodeId=" + nodeId + ", token=***]";
    }
}
