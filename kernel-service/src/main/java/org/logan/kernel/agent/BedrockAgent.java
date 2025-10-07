package org.logan.kernel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.logan.protocol.MessageEnvelope;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 🧩 BedrockAgent: kernel-side representation of a Bedrock agent process.
 *
 * Features:
 *  - Generic chat forwarding for normal agents
 *  - Orchestrator mode (id = orchestrator-agent): plans multi-agent workflows via LLM
 *  - Delegation protocol: kernel routes tasks between agents
 *  - Async communication with kernel
 *  - Emits reasoning events to kernel for unified timeline
 */
public class BedrockAgent implements Agent {
    private static final ScheduledExecutorService SCHED = Executors.newSingleThreadScheduledExecutor();
    private static final int MAX_ASYNC_RETRIES = 3;
    private static final long RETRY_BACKOFF_SECONDS = 1L;

    private final String id;
    private final String endpoint;
    private final Process process;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, CompletableFuture<Map<String, Object>>> localWaiters = new ConcurrentHashMap<>();

    private static String waiterKey(String sessionId, String agentId) {
        return sessionId + "::" + agentId;
    }

    public BedrockAgent(String id, String endpoint, Process process) {
        this.id = id;
        this.endpoint = endpoint;
        this.process = process;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public String getId() { return id; }

    @Override
    public String getType() { return "BEDROCK"; }

    @Override
    public String getEndpoint() { return endpoint; }

    @Override
    public void handleMessage(MessageEnvelope<?> envelope) {
        try {
            System.out.printf("📩 [%s] Received message from=%s type=%s%n",
                    id, envelope.getSenderId(), envelope.getType());

            String type = envelope.getType() == null ? "" : envelope.getType().toLowerCase();

            switch (type) {
                case "chat" -> {
                    if ("orchestrator-agent".equalsIgnoreCase(this.id)) {
                        handleOrchestratorChat(envelope);
                    } else {
                        handleChat(envelope);
                    }
                }
                case "tool_request" ->
                        System.out.printf("🧰 [%s] tool_request forwarded: %s%n", id, envelope.getPayload());
                case "chat_result" -> {
                    if ("orchestrator-agent".equalsIgnoreCase(this.id)) {
                        Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();
                        String sessionId = Optional.ofNullable(payload.get("sessionId"))
                                .map(Object::toString).orElse("default-session");
                        String agentId = Optional.ofNullable(payload.get("agentId"))
                                .map(Object::toString).orElse(envelope.getSenderId());

                        completeLocalWaiter(sessionId, agentId, payload);

                        System.out.printf("✅ [%s] (orchestrator) Received chat_result from %s for session=%s — waiter completed%n",
                                id, agentId, sessionId);
                    } else {
                        MessageEnvelope<Object> out = new MessageEnvelope<>();
                        out.setSenderId(this.id);
                        out.setRecipientId("kernel");
                        out.setType("chat_result");
                        out.setPayload(envelope.getPayload());
                        sendToKernel(out);
                        System.out.printf("📤 [%s] Forwarded chat_result to kernel%n", id);
                    }
                    break;
                }
                default ->
                        System.out.printf("⚠️ [%s] Unknown message type: %s%n", id, type);
            }

        } catch (Exception e) {
            System.err.printf("❌ [%s] Error handling message: %s%n", id, e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 🔹 Standard agent chat flow
     */
    private void handleChat(MessageEnvelope<?> envelope) {
        try {
            Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();
            String sessionId = (String) payload.getOrDefault("sessionId", "default-session");
            String message = (String) payload.get("message");

            if (message == null || message.isEmpty()) {
                System.out.printf("⚠️ [%s] Empty message for session=%s%n", id, sessionId);
                return;
            }

            Map<String,Object> body = Map.of(
                    "message", message,
                    "agentId", this.id
            );

            String url = String.format("%s/chat/%s", endpoint, sessionId);
            System.out.printf("🌐 [%s] Forwarding chat to %s | body=%s%n", id, url, body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String,Object> llmResponse = objectMapper.readValue(resp.body(), Map.class);

            llmResponse.putIfAbsent("sessionId", sessionId);
            llmResponse.putIfAbsent("agentId", this.id);

            MessageEnvelope<Object> out = new MessageEnvelope<>();
            out.setSenderId(this.id);
            out.setRecipientId(envelope.getSenderId());
            out.setType("chat_result");
            out.setPayload(llmResponse);

            sendToKernel(out);
            System.out.printf("📤 [%s] Sent chat_result to kernel/originator%n", id);

        } catch (Exception e) {
            System.err.printf("❌ [%s] handleChat failed: %s%n", id, e.getMessage());
        }
    }

    /**
     * 🔹 Orchestrator mode — executes agents in strict sequence
     */
    private void handleOrchestratorChat(MessageEnvelope<?> envelope) {
        try {
            Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();
            String sessionId = (String) payload.getOrDefault("sessionId", UUID.randomUUID().toString());
            String message = (String) payload.get("message");
            if (message == null || message.isEmpty()) return;

            sendReasoningUpdate(sessionId, "thinking",
                    "<thinking>Planning how to achieve: " + message + "</thinking>");
            System.out.printf("🧭 [orchestrator] Planning generically for session=%s%n", sessionId);

            Map<String, List<String>> agentTools = discoverAllAgentTools();
            sendReasoningUpdate(sessionId, "planner", "Discovered agent tools: " + agentTools);
            System.out.printf("🧩 [orchestrator] Discovered agent tools: %s%n", agentTools);

            String planningPrompt = """
                You are an AI orchestrator responsible for planning multi-agent workflows.
                Given the user's goal and the available agents with their tools,
                decide which agents should be called and in what order.
                
                Respond strictly as JSON:
                {
                    "plan": [
                        {"agent": "agent-id", "action": "Describe step"},
                        {"agent": "agent-id-2", "action": "Describe next step"}
                    ]
                }

                User goal: %s
                Available agents and tools: %s
            """.formatted(message, agentTools);

            List<Map<String, Object>> plan = askModelForPlan(planningPrompt);
            sendReasoningUpdate(sessionId, "planner", "Generated plan: " + plan);

            // Execute each agent sequentially
            for (Map<String, Object> step : plan) {
                String agent = (String) step.get("agent");
                String action = (String) step.get("action");
                if (agent == null || action == null) continue;

                sendReasoningUpdate(sessionId, "delegation",
                        "Delegating to " + agent + " → " + action);

                CompletableFuture<Map<String, Object>> waiter = new CompletableFuture<>();
                registerLocalWaiter(sessionId, agent, waiter);
                registerWaiterWithKernel(sessionId, agent, waiter);

                delegateToAgent(agent, sessionId, action);
                System.out.printf("🤝 [%s] Delegated session=%s → %s%n", id, sessionId, agent);

                try {
                    // ⏳ Wait indefinitely for result (sequential)
                    Map<String, Object> agentResult = waiter.get();

                    if (agentResult == null || agentResult.isEmpty()) {
                        sendReasoningUpdate(sessionId, "orchestration_step_warning",
                                "⚠️ " + agent + " returned empty result — continuing");
                    } else {
                        sendReasoningUpdate(sessionId, "orchestration_step_complete",
                                "✅ " + agent + " completed → " + agentResult.getOrDefault("status", "ok"));
                    }

                } catch (Exception e) {
                    sendReasoningUpdate(sessionId, "orchestration_step_failed",
                            "❌ " + agent + " failed due to " + e.getMessage() + " — continuing");
                    continue;
                }
            }

            sendReasoningUpdate(sessionId, "summary", "All delegations completed.");

            MessageEnvelope<Object> ack = new MessageEnvelope<>();
            ack.setSenderId(this.id);
            ack.setRecipientId(envelope.getSenderId());
            ack.setType("chat_result");
            ack.setPayload(Map.of(
                    "sessionId", sessionId,
                    "status", "delegations_completed",
                    "plan", plan
            ));
            sendToKernel(ack);

        } catch (Exception e) {
            System.err.printf("❌ [orchestrator] failed: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendReasoningUpdate(String sessionId, String phase, String message) {
        try {
            MessageEnvelope<Map<String, Object>> env = new MessageEnvelope<>();
            env.setSenderId(this.id);
            env.setRecipientId("kernel");
            env.setType("agent_status_update");
            env.setPayload(Map.of(
                    "sessionId", sessionId,
                    "phase", phase,
                    "message", message,
                    "agentId", this.id
            ));
            sendToKernel(env);
        } catch (Exception e) {
            System.err.printf("⚠️ [%s] Failed reasoning update: %s%n", this.id, e.getMessage());
        }
    }

    private Map<String, List<String>> discoverAllAgentTools() {
        Map<String, List<String>> agentTools = new HashMap<>();
        try {
            String kernelAgentsUrl = System.getProperty("kernel.url", "http://localhost:8080") + "/agents";
            HttpRequest agentsReq = HttpRequest.newBuilder()
                    .uri(URI.create(kernelAgentsUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> agentsResp = httpClient.send(agentsReq, HttpResponse.BodyHandlers.ofString());
            Map<String,Object> root = objectMapper.readValue(agentsResp.body(), Map.class);
            List<Map<String,Object>> agents = (List<Map<String,Object>>) root.getOrDefault("agents", List.of());

            for (Map<String,Object> a : agents) {
                String agentId = (String) a.get("agentId");
                String endpoint = (String) a.get("endpoint");
                if (endpoint == null) continue;
                try {
                    HttpResponse<String> toolsResp = httpClient.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(endpoint + "/tools/list"))
                                    .timeout(Duration.ofSeconds(5))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    List<Map<String,Object>> toolList = objectMapper.readValue(toolsResp.body(), List.class);
                    List<String> names = toolList.stream()
                            .map(m -> (String) m.get("name"))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    agentTools.put(agentId, names);
                } catch (Exception ex) {
                    System.err.printf("⚠️ [orchestrator] Failed tools fetch for %s: %s%n", agentId, ex.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.printf("❌ discoverAllAgentTools failed: %s%n", e.getMessage());
        }
        return agentTools;
    }

    private List<Map<String, Object>> askModelForPlan(String prompt) {
        try {
            String url = endpoint + "/chat/planner";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("message", prompt))))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String,Object> parsed = objectMapper.readValue(resp.body(), Map.class);
            return (List<Map<String, Object>>) parsed.getOrDefault("plan", List.of());
        } catch (Exception e) {
            System.err.printf("⚠️ Plan generation failed: %s%n", e.getMessage());
            return List.of();
        }
    }

    public void registerAgentToKernel(String targetAgent, String sessionId, String message) {
        try {
            Map<String,Object> payload = Map.of(
                    "targetAgent", targetAgent,
                    "sessionId", sessionId,
                    "message", message,
                    "fromAgent", this.id
            );

            MessageEnvelope<Map<String,Object>> env = new MessageEnvelope<>();
            env.setSenderId(this.id);
            env.setRecipientId("kernel");
            env.setType("register_agent_plan");
            env.setPayload(payload);
            sendToKernel(env);

            System.out.printf("🤝 [%s] Registered agent for  session=%s → %s%n", this.id, sessionId, targetAgent);
        } catch (Exception e) {
            System.err.printf("❌ [%s] Registering agent failed: %s%n", this.id, e.getMessage());
        }
    }

    public void delegateToAgent(String targetAgent, String sessionId, String message) {
        try {
            Map<String,Object> payload = Map.of(
                    "targetAgent", targetAgent,
                    "sessionId", sessionId,
                    "message", message,
                    "fromAgent", this.id
            );

            MessageEnvelope<Map<String,Object>> env = new MessageEnvelope<>();
            env.setSenderId(this.id);
            env.setRecipientId("kernel");
            env.setType("delegation");
            env.setPayload(payload);
            sendToKernel(env);

            System.out.printf("🤝 [%s] Delegated session=%s → %s%n", this.id, sessionId, targetAgent);
        } catch (Exception e) {
            System.err.printf("❌ [%s] delegateToAgent failed: %s%n", this.id, e.getMessage());
        }
    }

    private void sendToKernel(MessageEnvelope<?> message) {
        try {
            String kernelUrl = System.getProperty("kernel.url", "http://localhost:8080") + "/messages";
            String json = objectMapper.writeValueAsString(message);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(kernelUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(100))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            String type = message.getType() != null ? message.getType().toLowerCase() : "";
            boolean isCritical =
                    type.equals("register_waiter") ||
                            type.equals("agent_status_update");

            if (isCritical) {
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                System.out.printf("📤 [%s] (SYNC) Sent %s to kernel | response=%s%n",
                        id, type, resp.body());
            } else {
                sendAsyncWithRetries(req, type, 1);
            }

        } catch (Exception e) {
            System.err.printf("❌ [%s] sendToKernel failed: %s%n", id, e.getMessage());
        }
    }

    @Override
    public void onStart() {
        System.out.println("▶ BedrockAgent " + id + " is live at " + endpoint);
    }

    @Override
    public void onStop() {
        if (process != null && process.isAlive()) process.destroy();
    }

    private boolean registerWaiterWithKernel(String sessionId, String agentId, CompletableFuture<Map<String, Object>> waiter) {
        try {
            String kernelUrl = System.getProperty("kernel.url", "http://localhost:8080") + "/messages/register-waiter";
            Map<String, Object> payload = Map.of("sessionId", sessionId, "agentId", agentId);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(kernelUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                System.out.printf("📡 [%s] Registered waiter for %s (kernel response: %s)%n", this.id, agentId, resp.body());
                return true;
            } else {
                System.err.printf("⚠️ [%s] register-waiter failed for %s: %d%n", this.id, agentId, resp.statusCode());
                return false;
            }
        } catch (Exception ex) {
            System.err.printf("⚠️ [%s] registerWaiterWithKernel failed: %s%n", this.id, ex.getMessage());
            return false;
        }
    }

    private void sendAsyncWithRetries(HttpRequest req, String type, int attempt) {
        httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> System.out.printf("📤 [%s] (ASYNC) Sent %s | response=%s%n", id, type, resp.body()))
                .exceptionally(ex -> {
                    if (attempt < MAX_ASYNC_RETRIES) {
                        long delay = RETRY_BACKOFF_SECONDS * (1L << (attempt - 1));
                        System.err.printf("⚠️ [%s] Failed %s attempt %d/%d: %s — retrying in %ds%n",
                                id, type, attempt, MAX_ASYNC_RETRIES, ex.getMessage(), delay);
                        SCHED.schedule(() -> sendAsyncWithRetries(req, type, attempt + 1), delay, TimeUnit.SECONDS);
                    } else {
                        System.err.printf("❌ [%s] Permanent failure sending %s after %d attempts%n",
                                id, type, attempt);
                    }
                    return null;
                });
    }

    private void registerLocalWaiter(String sessionId, String agentId, CompletableFuture<Map<String, Object>> fut) {
        localWaiters.put(waiterKey(sessionId, agentId), fut);
        System.out.printf("📡 [%s] (local) Registered waiter for session=%s agent=%s%n", id, sessionId, agentId);
    }

    private void completeLocalWaiter(String sessionId, String agentId, Map<String, Object> payload) {
        String key = waiterKey(sessionId, agentId);
        CompletableFuture<Map<String, Object>> fut = localWaiters.remove(key);
        if (fut != null) {
            Map<String, Object> safeResult = new LinkedHashMap<>();
            if (payload != null) safeResult.putAll(payload);
            safeResult.putIfAbsent("status", "ok");
            safeResult.putIfAbsent("agentId", agentId);
            safeResult.putIfAbsent("sessionId", sessionId);
            fut.complete(safeResult);
            System.out.printf("✅ [%s] (local) Completed waiter for session=%s agent=%s%n", id, sessionId, agentId);
        } else {
            System.out.printf("⚠️ [%s] (local) No waiter found for session=%s agent=%s%n", id, sessionId, agentId);
        }
    }
}
