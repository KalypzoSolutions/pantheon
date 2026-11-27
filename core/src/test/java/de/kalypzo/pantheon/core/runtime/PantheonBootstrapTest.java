package de.kalypzo.pantheon.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.agora.proto.v1.AgoraGrpc;
import de.kalypzo.agora.proto.v1.NodeMessage;
import de.kalypzo.agora.proto.v1.ServiceMessage;
import de.kalypzo.pantheon.core.backend.ConnectionState;
import de.kalypzo.pantheon.core.backend.agora.AgoraBackend;
import de.kalypzo.pantheon.core.backend.memory.InMemoryNetwork;
import de.kalypzo.pantheon.data.DataNamespace;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bootstrap from the environment (P-23, E12, R11, R12, R49). */
class PantheonBootstrapTest {

    private static final String TOKEN = "s3cr3t-token-value";

    @TempDir
    Path dir;

    private BootstrapResult result;
    private Server server;

    @AfterEach
    void tearDown() throws Exception {
        if (result instanceof BootstrapResult.Started s) s.close();
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void memoryBackendStartsWithoutAgoraEnv() {
        result = Bootstrap.start(Map.of("PANTHEON_BACKEND", "memory"), dir, Duration.ofMillis(200));

        assertThat(result).isInstanceOf(BootstrapResult.Started.class);
        BootstrapResult.Started s = (BootstrapResult.Started) result;
        assertThat(s.backend()).isInstanceOf(InMemoryNetwork.Node.class);
        assertThat(s.network()).isPresent();
        assertThat(s.nodeId()).isEqualTo("local");
        assertThat(s.backend().state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(s.loginTimeout()).isEqualTo(Duration.ofMillis(5000));
        assertThat(Files.exists(dir.resolve("outbox.db"))).isTrue();
        assertThat(s.writer()).isNotNull();
        assertThat(s.drainer()).isNotNull();
        assertThat(s.players()).isNotNull();
        assertThat(s.stores()).isNotNull();
        assertThat(s.presence()).isNotNull();
        assertThat(s.sync()).isNotNull();
        // the stores and players are usable right away
        s.stores().store(DataNamespace.of("lobby"), "clans").doc("c1")
                .inc(DataNamespace.of("lobby").ofLong("coins", 0L), 3);
    }

    @Test
    void closeStopsOwnThreads() {
        BootstrapResult.Started s = (BootstrapResult.Started)
                Bootstrap.start(Map.of("PANTHEON_BACKEND", "memory"), dir, Duration.ofMillis(200));
        assertThat(s.executors()).hasSize(2); // writer callbacks and the own listener thread of the 3-arg form
        s.close();
        assertThat(s.executors()).allMatch(java.util.concurrent.ExecutorService::isShutdown);
        assertThat(s.scheduler().isShutdown()).isTrue();
    }

    @Test
    void loginTimeoutFromEnv() {
        result = Bootstrap.start(Map.of("PANTHEON_BACKEND", "memory", "PANTHEON_NODE_ID", "lobby-01",
                "PANTHEON_LOGIN_TIMEOUT_MS", "1234"), dir, Duration.ofMillis(200));
        BootstrapResult.Started s = (BootstrapResult.Started) result;
        assertThat(s.loginTimeout()).isEqualTo(Duration.ofMillis(1234));
        assertThat(s.nodeId()).isEqualTo("lobby-01");
    }

    @Test
    void invalidLoginTimeoutIsConfigError() {
        result = Bootstrap.start(Map.of("PANTHEON_BACKEND", "memory", "PANTHEON_LOGIN_TIMEOUT_MS", "soon"), dir,
                Duration.ofMillis(200));
        assertThat(result).isInstanceOf(BootstrapResult.ConfigError.class);
        assertThat(((BootstrapResult.ConfigError) result).message()).contains("PANTHEON_LOGIN_TIMEOUT_MS");
    }

    @Test
    void missingAgoraEnvIsConfigError() {
        result = Bootstrap.start(Map.of(), dir, Duration.ofMillis(200));

        assertThat(result).isInstanceOf(BootstrapResult.ConfigError.class);
        assertThat(((BootstrapResult.ConfigError) result).message()).contains("PANTHEON_AGORA_ADDRESS");

        result = Bootstrap.start(Map.of("PANTHEON_BACKEND", "agora", "PANTHEON_AGORA_ADDRESS", "agora:9090",
                "PANTHEON_NODE_ID", "lobby-01"), dir, Duration.ofMillis(200));
        assertThat(result).isInstanceOf(BootstrapResult.ConfigError.class);
        assertThat(((BootstrapResult.ConfigError) result).message()).contains("PANTHEON_AGORA_TOKEN");
    }

    @Test
    void unauthenticatedIsConfigError() throws Exception {
        server = NettyServerBuilder.forPort(0).addService(new AgoraGrpc.AgoraImplBase() {
            @Override
            public StreamObserver<NodeMessage> connect(StreamObserver<ServiceMessage> responses) {
                responses.onError(Status.UNAUTHENTICATED.asRuntimeException());
                return new StreamObserver<>() {
                    @Override
                    public void onNext(NodeMessage value) {}

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onCompleted() {}
                };
            }
        }).build().start();

        long start = System.nanoTime();
        result = Bootstrap.start(agoraEnv("localhost:" + server.getPort()), dir, Duration.ofSeconds(5));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result).isInstanceOf(BootstrapResult.ConfigError.class);
        String message = ((BootstrapResult.ConfigError) result).message();
        assertThat(message).contains("UNAUTHENTICATED").doesNotContain(TOKEN);
        assertThat(took).isLessThan(Duration.ofSeconds(5)); // the outcome ends the wait, not the timeout
    }

    @Test
    void unreachableAgoraStartsAnyway() {
        long start = System.nanoTime();
        result = Bootstrap.start(agoraEnv("localhost:1"), dir, Duration.ofMillis(200),
                Runnable::run, Logger.getLogger("PantheonBootstrapTest"));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result).isInstanceOf(BootstrapResult.Started.class);
        BootstrapResult.Started s = (BootstrapResult.Started) result;
        assertThat(s.backend()).isInstanceOf(AgoraBackend.class);
        assertThat(s.network()).isEmpty();
        assertThat(s.nodeId()).isEqualTo("lobby-01");
        assertThat(s.backend().state()).isNotIn(ConnectionState.CONNECTED, ConnectionState.FATAL);
        assertThat(took).isLessThan(Duration.ofSeconds(3));
    }

    private static Map<String, String> agoraEnv(String address) {
        return Map.of("PANTHEON_AGORA_ADDRESS", address, "PANTHEON_NODE_ID", "lobby-01",
                "PANTHEON_AGORA_TOKEN", TOKEN);
    }
}
