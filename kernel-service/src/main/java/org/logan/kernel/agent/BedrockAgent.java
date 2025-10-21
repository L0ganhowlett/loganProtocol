package org.logan.kernel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.logan.protocol.MessageEnvelope;

import java.io.File;
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
 * Event-driven orchestration (non-blocking)
 *  ✅ Uses waiter-based flow (no while-loops)
 *  ✅ Supports user input, retry, skip
 *  ✅ Can persist orchestration state between restarts
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

    // 🆕 Persistent session orchestration state
    private final Map<String, List<Map<String, Object>>> sessionPlans = new ConcurrentHashMap<>();
    private final Map<String, Integer> sessionStepIndex = new ConcurrentHashMap<>();
    private final Map<String, String> sessionPausedAgent = new ConcurrentHashMap<>();

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
        // 🆕 Load persisted orchestration state (if exists)
        loadSessionState();
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
                    Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();
                    String sessionId = (String) payload.getOrDefault("sessionId", UUID.randomUUID().toString());
                    String message = (String) payload.get("message");

                    // 🆕 Check if there’s a paused session
                    if ("orchestrator-agent".equalsIgnoreCase(this.id)
                            && sessionPlans.containsKey(sessionId)
                            && sessionStepIndex.containsKey(sessionId)) {

                        System.out.printf("🔁 [%s] Resuming paused orchestration for session=%s with message=%s%n",
                                id, sessionId, message);

                        resumePausedSession(sessionId, message);
                    } else {
                        // Normal new orchestration
                        if ("orchestrator-agent".equalsIgnoreCase(this.id)) {
                            handleOrchestratorChat(envelope);
                        } else {
                            handleChat(envelope);
                        }
                    }
                    break;
                }
                case "chat_result" -> {
                    if ("orchestrator-agent".equalsIgnoreCase(this.id)) {
                        Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();
                        String sessionId = Optional.ofNullable(payload.get("sessionId"))
                                .map(Object::toString).orElse("default-session");
                        String agentId = Optional.ofNullable(payload.get("agentId"))
                                .map(Object::toString).orElse(envelope.getSenderId());

                        completeLocalWaiter(sessionId, agentId, payload);
                        // 🆕 Trigger reasoning and next step
                        handleAgentResult(sessionId, agentId, payload);

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
                case "user_decision" -> {
                    Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();
                    String sessionId = Optional.ofNullable(payload.get("sessionId"))
                            .map(Object::toString).orElse("default-session");
                    String agent = (String) payload.get("agentId");
                    if (agent == null || agent.equalsIgnoreCase("unknown")) {
                        agent = sessionPausedAgent.get(sessionId);
                    }

                    String action = (String) payload.getOrDefault("action", "");
                    handleUserDecision(sessionId, agent, action, payload);
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

    // 🔹 Standard agent chat flow (unchanged)
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

    // 🔄 Replaced blocking orchestrator with event-driven orchestration
    private void handleOrchestratorChat(MessageEnvelope<?> envelope) {
        try {

            Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();
            String sessionId = (String) payload.getOrDefault("sessionId", UUID.randomUUID().toString());
            String message = (String) payload.get("message");
            if (message == null || message.isEmpty()) return;
            sendReasoningUpdate(sessionId, "thinking", "<thinking>Planning how to achieve: " + message + "</thinking>");
            System.out.printf("🧭 [orchestrator] Planning generically for session=%s%n", sessionId);

            Map<String, List<String>> agentTools = discoverAllAgentTools();
            sendReasoningUpdate(sessionId, "planner", "Discovered agent tools: " + agentTools);

            String planningPrompt = """
            You are an AI orchestrator responsible for planning multi-agent workflows.
            Given the user's goal and the available agents with their tools,
            decide which agents should be called and in what order.

            Respond ONLY with valid JSON (no markdown):
            { "plan": [ {"agent":"agent-id","action":"Describe step"} ] }

            User goal: %s
            Available agents and tools: %s
            """.formatted(message, agentTools);

            List<Map<String, Object>> plan = askModelForPlan(planningPrompt);
            sendReasoningUpdate(sessionId, "planner", "Generated plan: " + plan);

            // 🆕 Save session plan
            sessionPlans.put(sessionId, plan);
            sessionStepIndex.put(sessionId, 0);
            persistSessionState();

            // 🆕 Begin orchestration
            continuePlan(sessionId);

        } catch (Exception e) {
            System.err.printf("❌ [orchestrator] failed: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }

    // 🆕 Move to the next agent step
    private void continuePlan(String sessionId) {
        List<Map<String, Object>> plan = sessionPlans.get(sessionId);
        int index = sessionStepIndex.getOrDefault(sessionId, 0);

        if (plan == null || index >= plan.size()) {
            sendReasoningUpdate(sessionId, "summary", "✅ All delegations completed.");
            sessionPlans.remove(sessionId);
            sessionStepIndex.remove(sessionId);
            persistSessionState();
            return;
        }

        Map<String, Object> step = plan.get(index);
        String agent = (String) step.get("agent");
        String action = (String) step.get("action");

        sendReasoningUpdate(sessionId, "delegation", "Delegating to " + agent + " → " + action);

        CompletableFuture<Map<String, Object>> waiter = new CompletableFuture<>();
        registerLocalWaiter(sessionId, agent, waiter);
        registerWaiterWithKernel(sessionId, agent, waiter);

        delegateToAgent(agent, sessionId, action);
        System.out.printf("🤝 [%s] Delegated session=%s → %s%n", id, sessionId, agent);
    }

    // 🆕 Triggered when agent completes
    private void handleAgentResult(String sessionId, String agent, Map<String, Object> result) {
        try {
            String message = extractAgentMessage(result);
            Map<String, Object> reason = askModelForReason(agent, message);

            boolean needsUserInput = Boolean.TRUE.equals(reason.get("needsUserInput"));
            boolean toolFailed = Boolean.TRUE.equals(reason.get("toolFailed"));
            String reasonText = Optional.ofNullable(reason.get("reason"))
                    .map(Object::toString)
                    .orElse("Detected issue");

            if (needsUserInput || toolFailed) {
                String pausedAgent = sessionPausedAgent.get(sessionId);

                // 🔹 If session not currently paused, or paused for a different agent → pause now
                if (pausedAgent == null || !pausedAgent.equals(agent)) {
                    sessionPausedAgent.put(sessionId, agent);
                    sendReasoningUpdate(sessionId, "orchestration_pause",
                            "⏸ " + reasonText + " for " + agent);

                    Map<String, Object> waitPayload = Map.of(
                            "type", "orchestrator_wait",
                            "sessionId", sessionId,
                            "agentId", agent,
                            "reason", reasonText,
                            "message", message,
                            "options", List.of("provide_input", "skip", "abort", "retry")
                    );

                    MessageEnvelope<Map<String, Object>> waitEvent = new MessageEnvelope<>();
                    waitEvent.setSenderId(this.id);
                    waitEvent.setRecipientId("kernel");
                    waitEvent.setType("orchestrator_wait");
                    waitEvent.setPayload(waitPayload);
                    sendToKernel(waitEvent);

                    System.out.printf("⏸ [%s] Paused session=%s for agent=%s%n", id, sessionId, agent);
                    return;
                }

                // 🔁 If already paused for this agent → just refresh reasoning
                sendReasoningUpdate(sessionId, "orchestration_pause_update",
                        "🔁 Updated reasoning for " + agent + ": " + reasonText);
                System.out.printf("⚠️ [%s] Already paused for agent=%s — reasoning refreshed%n", id, agent);
                return;
            }

            // ✅ Success → clear pause and move next
            sendReasoningUpdate(sessionId, "orchestration_step_complete",
                    "✅ " + agent + " completed successfully.");

            sessionPausedAgent.remove(sessionId);
            int next = sessionStepIndex.getOrDefault(sessionId, 0) + 1;
            sessionStepIndex.put(sessionId, next);
            persistSessionState();
            continuePlan(sessionId);

        } catch (Exception e) {
            sendReasoningUpdate(sessionId, "orchestration_step_failed",
                    "❌ " + agent + " failed: " + e.getMessage());
        }
    }

    // 🆕 Handle user decision inputs
    private void handleUserDecision(String sessionId, String agent, String action, Map<String, Object> decision) {
        String choice = ((String) decision.getOrDefault("choice", "skip")).toLowerCase();

        switch (choice) {
            case "abort" -> sendReasoningUpdate(sessionId, "orchestration_abort", "🛑 User aborted orchestration");

            case "retry" -> {
                sendReasoningUpdate(sessionId, "orchestration_retry", "🔁 Retrying " + agent);
                // ✅ Clear paused state before retry
                sessionPausedAgent.remove(sessionId);

                CompletableFuture<Map<String, Object>> waiter = new CompletableFuture<>();
                registerLocalWaiter(sessionId, agent, waiter);
                registerWaiterWithKernel(sessionId, agent, waiter);
                delegateToAgent(agent, sessionId, action);
            }

            case "provide_input" -> {
                String input = (String) decision.getOrDefault("input", "");
                sendReasoningUpdate(sessionId, "orchestration_resume_input",
                        "💡 Received user input for " + agent + ": " + input);

                // ✅ Clear paused state before resuming
                sessionPausedAgent.remove(sessionId);

                CompletableFuture<Map<String, Object>> waiter = new CompletableFuture<>();
                registerLocalWaiter(sessionId, agent, waiter);
                registerWaiterWithKernel(sessionId, agent, waiter);

                // 🧠 Re-delegate with the user input
                delegateToAgent(agent, sessionId, action + " (user input: " + input + ")");
            }


            default -> {
                sendReasoningUpdate(sessionId, "orchestration_skip", "⏭ Skipping " + agent);
                int next = sessionStepIndex.getOrDefault(sessionId, 0) + 1;
                sessionStepIndex.put(sessionId, next);
                persistSessionState();
                continuePlan(sessionId);
            }
        }
    }

    // 🆕 Helper to extract agent readable message
    private String extractAgentMessage(Map<String, Object> agentResult) {
        // 🧠 First, check if there’s a top-level message
        String msg = Objects.toString(agentResult.getOrDefault("message", ""), "");
        if (!msg.isBlank()) return msg;

        // 🧾 Otherwise, check the audit log and take only the latest message
        if (agentResult.containsKey("audit")) {
            Object auditObj = agentResult.get("audit");
            if (auditObj instanceof List<?> list && !list.isEmpty()) {
                // 🕐 Get the last element (latest audit entry)
                Object last = list.get(list.size() - 1);
                if (last instanceof Map<?, ?> m && m.containsKey("message")) {
                    return Objects.toString(m.get("message"), "").trim();
                }
            }
        }

        return "No explicit message from agent.";
    }


    // 🆕 Persist orchestration state to disk
    private synchronized void persistSessionState() {
        try {
            Map<String, Object> state = Map.of(
                    "plans", sessionPlans,
                    "indexes", sessionStepIndex
            );
            objectMapper.writeValue(new File("orchestrator_state.json"), state);
        } catch (Exception e) {
            System.err.printf("⚠️ [%s] Failed to persist state: %s%n", id, e.getMessage());
        }
    }

    // 🆕 Load persisted state from disk
    private synchronized void loadSessionState() {
        try {
            File file = new File("orchestrator_state.json");
            if (!file.exists()) return;
            Map<String, Object> state = objectMapper.readValue(file, Map.class);
            sessionPlans.putAll((Map<String, List<Map<String, Object>>>) state.getOrDefault("plans", Map.of()));
            Map<String, Integer> idx = (Map<String, Integer>) state.getOrDefault("indexes", Map.of());
            sessionStepIndex.putAll(idx);
            System.out.println("🔄 [orchestrator] Restored persisted orchestration state");
        } catch (Exception e) {
            System.err.printf("⚠️ [%s] Failed to load persisted state: %s%n", id, e.getMessage());
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
            String body = resp.body();

            // 🆕 1️⃣ Clean Bedrock/Nova LLM responses that include markdown fences
            String cleaned = body.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("(?s)```json|```", "").trim();
            }

            // 🆕 2️⃣ Log cleaned content for debugging
            System.out.printf("🧩 [planner] Raw model response (cleaned): %s%n", cleaned);

            // 🆕 3️⃣ Try to parse the cleaned JSON safely
            Map<String, Object> parsed = new HashMap<>();
            try {
                parsed = objectMapper.readValue(cleaned, Map.class);
            } catch (Exception inner) {
                System.err.printf("⚠️ [planner] JSON parsing failed, returning raw text. Cause: %s%n", inner.getMessage());
                parsed.put("raw", cleaned);
                parsed.put("error", "Could not parse JSON plan, returning raw text.");
            }

            // 🆕 4️⃣ Extract plan if present
            Object planObj = parsed.get("plan");
            if (planObj instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            } else {
                System.err.println("⚠️ [planner] 'plan' field missing or not a list — returning empty plan.");
                return List.of();
            }

        } catch (Exception e) {
            System.err.printf("❌ [planner] Plan generation failed: %s%n", e.getMessage());
            return List.of();
        }
    }

    /**
     * 🧠 askModelForReason — Ask the model to reason about an agent's message
     * Determines if the agent output indicates:
     *   - a need for user input (needsUserInput = true)
     *   - a tool or process failure (toolFailed = true)
     * Returns clean structured JSON, same as askModelForPlan.
     */
    private Map<String, Object> askModelForReason(String agent, String agentMessage) {
        try {
            String reasoningPrompt = """
        You are an AI orchestration reasoning assistant.
        Given an agent's last message, analyze whether it indicates a failure
        or is asking for user input. Return a structured JSON decision.

        Respond ONLY with valid JSON (no markdown). The format must be parsable directly:
        {
            "needsUserInput": true | false,
            "toolFailed": true | false,
            "reason": "<short readable reason>",
            "options": ["provide_input","skip","abort","retry"]
        }

        Criteria:
        - needsUserInput: true if the agent requests missing information, parameters, or clarification from the user.
        - toolFailed: true if the agent reports an error, exception, or tool failure.
        - reason: a brief one-line summary.
        - options: must always include exactly ["provide_input","skip","abort","retry"].

        Agent ID: %s
        Message: %s
        """.formatted(agent, agentMessage == null ? "" : agentMessage);

            String url = endpoint + "/chat/planner"; // same endpoint as plan
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of("message", reasoningPrompt))
                    ))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            // 🧹 Clean up markdown fences if LLM adds them
            String cleaned = body == null ? "" : body.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("(?s)```json|```", "").trim();
            }

            System.out.printf("🧩 [reasoner] Raw model response (cleaned): %s%n", cleaned);

            // 🧮 Parse JSON safely
            Map<String, Object> parsed = new HashMap<>();
            try {
                parsed = objectMapper.readValue(cleaned, Map.class);
            } catch (Exception inner) {
                System.err.printf("⚠️ [reasoner] JSON parsing failed, fallback to heuristic. Cause: %s%n", inner.getMessage());
                parsed.put("raw", cleaned);
                parsed.put("error", "Could not parse JSON reasoning output.");
            }

            // 🧠 Enforce defaults if fields are missing or malformed
            String msg = agentMessage == null ? "" : agentMessage.toLowerCase();
            boolean needsUserInput = parsed.containsKey("needsUserInput")
                    ? Boolean.TRUE.equals(parsed.get("needsUserInput"))
                    : (msg.contains("please provide") || msg.contains("missing") || msg.contains("need"));
            boolean toolFailed = parsed.containsKey("toolFailed")
                    ? Boolean.TRUE.equals(parsed.get("toolFailed"))
                    : (msg.contains("failed") || msg.contains("error") || msg.contains("exception"));
            String reason = Optional.ofNullable(parsed.get("reason"))
                    .map(Object::toString)
                    .orElseGet(() -> {
                        if (needsUserInput) return "Agent requested additional input";
                        if (toolFailed) return "Agent reported a failure";
                        return "Normal agent response";
                    });

            // ✅ Always enforce same 4 options
            parsed.put("needsUserInput", needsUserInput);
            parsed.put("toolFailed", toolFailed);
            parsed.put("reason", reason);
            parsed.put("options", List.of("provide_input", "skip", "abort", "retry"));

            return parsed;

        } catch (Exception e) {
            System.err.printf("❌ [reasoner] Reasoning model call failed: %s%n", e.getMessage());

            // Fallback if anything breaks
            String msg = agentMessage == null ? "" : agentMessage.toLowerCase();
            boolean needsUserInput = msg.contains("please provide") || msg.contains("missing") || msg.contains("need");
            boolean toolFailed = msg.contains("failed") || msg.contains("error") || msg.contains("exception");

            return Map.of(
                    "needsUserInput", needsUserInput,
                    "toolFailed", toolFailed,
                    "reason", needsUserInput
                            ? "User input required"
                            : (toolFailed ? "Agent process failed" : "Normal agent message"),
                    // ✅ Always same 4 options
                    "options", List.of("provide_input", "skip", "abort", "retry")
            );
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
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            String type = message.getType() != null ? message.getType().toLowerCase() : "";
            sendAsyncWithRetries(req, type, 1);

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

    // 🆕 Resume a paused orchestration when user provides input manually
    private void resumePausedSession(String sessionId, String userInput) {
        try {
            List<Map<String, Object>> plan = sessionPlans.get(sessionId);
            int index = sessionStepIndex.getOrDefault(sessionId, 0);

            if (plan == null || index >= plan.size()) {
                sendReasoningUpdate(sessionId, "summary", "✅ No pending steps to resume.");
                return;
            }

            Map<String, Object> currentStep = plan.get(index);
            String agent = (String) currentStep.get("agent");
            String action = (String) currentStep.get("action");

            sendReasoningUpdate(sessionId, "orchestration_resume_input",
                    "💡 User provided input mid-session for " + agent + ": " + userInput);

            CompletableFuture<Map<String, Object>> waiter = new CompletableFuture<>();
            registerLocalWaiter(sessionId, agent, waiter);
            registerWaiterWithKernel(sessionId, agent, waiter);

            // 🧠 Re-delegate agent with the user's new input
            delegateToAgent(agent, sessionId, action + " (user input: " + userInput + ")");
        } catch (Exception e) {
            System.err.printf("❌ [%s] Failed to resume paused session: %s%n", id, e.getMessage());
        }
    }

}
