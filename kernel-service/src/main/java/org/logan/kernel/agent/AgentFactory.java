package org.logan.kernel.agent;

import org.logan.kernel.persistence.AgentPersistenceService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Component
public class AgentFactory {

    private final AgentRegistry registry;
    private final AgentPersistenceService persistence;

    public AgentFactory(AgentRegistry registry, AgentPersistenceService persistence) {
        this.registry = registry;
        this.persistence = persistence;
    }

    public Agent createAgent(String id, String type) throws Exception {
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        switch (type.toUpperCase()) {
            case "BEDROCK":
                return createBedrockAgent(id, null);
            case "BEDROCK_SPAWNER":
                return createSpawnerAgent(id, null);
            default:
                throw new IllegalArgumentException("Unknown agent type: " + type);
        }
    }

    // ✅ Rehydrate or respawn if dead
    public Agent rehydrateAgent(String id, String type, String endpoint) throws Exception {
        if (isEndpointAlive(endpoint)) {
            System.out.println("♻️ Reattaching to live agent " + id + " at " + endpoint);
            return "BEDROCK_SPAWNER".equalsIgnoreCase(type)
                    ? new SpawnerBedrockAgent(id, endpoint, null, registry, this)
                    : new BedrockAgent(id, endpoint, null);
        } else {
            System.out.println("⚠️ Endpoint " + endpoint + " not alive, respawning " + id);
            return "BEDROCK_SPAWNER".equalsIgnoreCase(type)
                    ? createSpawnerAgent(id, null)
                    : createBedrockAgent(id, null);
        }
    }

    // ✅ Spawn new Spawner agent
    public Agent createSpawnerAgent(String id, String endpoint) throws Exception {
        int port = findFreePort();
        String assignedEndpoint = (endpoint != null) ? endpoint : "http://localhost:" + port;

        ProcessBuilder pb = new ProcessBuilder(
                "java", "-jar",
                "bedrock-agent/build/libs/bedrock-agent-1.0-SNAPSHOT.jar",
                "--server.port=" + port
        );
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process = pb.start();
        SpawnerBedrockAgent agent = new SpawnerBedrockAgent(id, assignedEndpoint, process, registry, this);

        persistence.upsertActive(id, "BEDROCK_SPAWNER", null, assignedEndpoint);
        registry.registerAgent(agent);
        return agent;
    }

    // ✅ Spawn new Bedrock agent
    public Agent createBedrockAgent(String id, String endpoint) throws Exception {
        int port = findFreePort();
        String assignedEndpoint = (endpoint != null) ? endpoint : "http://localhost:" + port;

        int debugPort = findFreePort(); // utility to pick a free port
        ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:" + debugPort,
                "-jar", "bedrock-agent/build/libs/bedrock-agent-1.0-SNAPSHOT.jar",
                "--server.port=" + port
        );
        System.out.println("🐞 Debug port for " + id + " = " + debugPort);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process = pb.start();
        BedrockAgent agent = new BedrockAgent(id, assignedEndpoint, process);

        persistence.upsertActive(id, "BEDROCK", null, assignedEndpoint);
        registry.registerAgent(agent);
        return agent;
    }

    // ✅ Health check with timeout
    private boolean isEndpointAlive(String endpoint) {
        if (endpoint == null) return false;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/actuator/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ✅ Find a free port
    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
