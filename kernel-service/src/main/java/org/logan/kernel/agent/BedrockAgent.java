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
                case "user_decision" -> {
                    Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();
                    String sessionId = Optional.ofNullable(payload.get("sessionId"))
                            .map(Object::toString).orElse("default-session");
                    String choice = Optional.ofNullable(payload.get("choice"))
                            .map(Object::toString).orElse("skip");
                    String input = Optional.ofNullable(payload.get("input"))
                            .map(Object::toString).orElse(null);

                    // complete local waiter for user so orchestration resumes
                    Map<String, Object> decisionData = new LinkedHashMap<>();
                    decisionData.put("choice", choice);
                    if (input != null && !input.isBlank()) {
                        decisionData.put("input", input);
                    }

                    completeLocalWaiter(sessionId, "user", decisionData);

                    System.out.printf("🧠 [%s] Received user decision for session=%s → choice=%s input=%s%n",
                            id, sessionId, choice, input);
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

        Respond ONLY with valid JSON. Do not use markdown or triple backticks.
        The response must be parsable directly as JSON:
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

            // 🔁 Execute each agent in sequence
            for (Map<String, Object> step : plan) {
                String agent = (String) step.get("agent");
                String action = (String) step.get("action");
                if (agent == null || action == null) continue;

                sendReasoningUpdate(sessionId, "delegation", "Delegating to " + agent + " → " + action);

                CompletableFuture<Map<String, Object>> waiter = new CompletableFuture<>();
                registerLocalWaiter(sessionId, agent, waiter);
                registerWaiterWithKernel(sessionId, agent, waiter);

                delegateToAgent(agent, sessionId, action);
                System.out.printf("🤝 [%s] Delegated session=%s → %s%n", id, sessionId, agent);

                try {
                    // Wait for agent to respond
                    Map<String, Object> agentResult = waiter.get();

                    // 🧩 Extract human-readable message for reasoning
                    String agentMessage = null;

                    // 1️⃣ Prefer top-level message
                    if (agentResult.containsKey("message")) {
                        agentMessage = Objects.toString(agentResult.get("message"), "");
                    }

                    // 2️⃣ Fallback: flatten audit messages
                    if ((agentMessage == null || agentMessage.isBlank()) && agentResult.containsKey("audit")) {
                        Object auditObj = agentResult.get("audit");
                        if (auditObj instanceof List<?> auditList) {
                            StringBuilder sb = new StringBuilder();
                            for (Object o : auditList) {
                                if (o instanceof Map<?, ?> m && m.containsKey("message")) {
                                    sb.append(m.get("message")).append(" | ");
                                }
                            }
                            agentMessage = sb.toString().trim();
                            if (agentMessage.endsWith("|")) {
                                agentMessage = agentMessage.substring(0, agentMessage.length() - 1);
                            }
                        }
                    }

                    // 3️⃣ Fallback if still empty
                    if (agentMessage == null || agentMessage.isBlank()) {
                        agentMessage = "Agent " + agent + " completed without explicit message.";
                    }

                    System.out.printf("🧠 Extracted agent message for reasoning: %s%n", agentMessage);

                    // 4️⃣ Analyze with reasoner
                    Map<String, Object> reasonAnalysis = askModelForReason(agent, agentMessage);

                    boolean needsUserInput = Boolean.TRUE.equals(reasonAnalysis.get("needsUserInput"));
                    boolean toolFailed = Boolean.TRUE.equals(reasonAnalysis.get("toolFailed"));
                    String reason = Optional.ofNullable(reasonAnalysis.get("reason"))
                            .map(Object::toString)
                            .orElse("Detected issue");

                    // ✅ Always enforce same 4 options here too
                    List<String> options = List.of("provide_input", "skip", "abort", "retry");

                    // 5️⃣ Pause orchestration if user action is needed
                    if (needsUserInput || toolFailed) {
                        sendReasoningUpdate(sessionId, "orchestration_pause", "⏸ " + reason + " for " + agent);

                        // 🧩 Send orchestrator_wait event to kernel/UI
                        Map<String, Object> waitPayload = Map.of(
                                "type", "orchestrator_wait",
                                "sessionId", sessionId,
                                "agentId", agent,
                                "reason", reason,
                                "message", agentMessage,
                                "options", options
                        );

                        MessageEnvelope<Map<String, Object>> waitEvent = new MessageEnvelope<>();
                        waitEvent.setSenderId(this.id);
                        waitEvent.setRecipientId("kernel");
                        waitEvent.setType("orchestrator_wait");
                        waitEvent.setPayload(waitPayload);
                        sendToKernel(waitEvent);

                        // 🔁 Wait for user decision
                        CompletableFuture<Map<String, Object>> userDecision = new CompletableFuture<>();
                        registerLocalWaiter(sessionId, "user", userDecision);
                        registerWaiterWithKernel(sessionId, "user", userDecision);

                        Map<String, Object> decision = userDecision.get();
                        String choice = ((String) decision.getOrDefault("choice", "skip")).toLowerCase();

                        switch (choice) {
                            case "abort" -> {
                                sendReasoningUpdate(sessionId, "orchestration_abort", "🛑 User aborted orchestration");
                                return;
                            }
                            case "retry" -> {
                                sendReasoningUpdate(sessionId, "orchestration_retry", "🔁 Retrying " + agent);
                                delegateToAgent(agent, sessionId, action);
                                waiter = new CompletableFuture<>();
                                registerLocalWaiter(sessionId, agent, waiter);
                                registerWaiterWithKernel(sessionId, agent, waiter);
                                agentResult = waiter.get();
                            }
                            case "provide_input" -> {
                                String userInput = (String) decision.getOrDefault("input", "");
                                sendReasoningUpdate(sessionId, "orchestration_resume_input",
                                        "💡 Received user input for " + agent + ": " + userInput);

                                // 🧩 Re-delegate with user input included
                                Map<String, Object> newPayload = Map.of(
                                        "targetAgent", agent,
                                        "sessionId", sessionId,
                                        "message", action + " (user input: " + userInput + ")",
                                        "fromAgent", this.id
                                );
                                MessageEnvelope<Map<String, Object>> newDelegation = new MessageEnvelope<>();
                                newDelegation.setSenderId(this.id);
                                newDelegation.setRecipientId("kernel");
                                newDelegation.setType("delegation");
                                newDelegation.setPayload(newPayload);
                                sendToKernel(newDelegation);

                                // Wait for completion
                                waiter = new CompletableFuture<>();
                                registerLocalWaiter(sessionId, agent, waiter);
                                registerWaiterWithKernel(sessionId, agent, waiter);
                                agentResult = waiter.get();
                            }
                            default -> {
                                sendReasoningUpdate(sessionId, "orchestration_skip", "⏭ Skipping " + agent);
                            }
                        }

                    }

                    // ✅ Finalize step
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
                    .timeout(Duration.ofSeconds(100))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            String type = message.getType() != null ? message.getType().toLowerCase() : "";
            boolean isCritical =
                    type.equals("register_waiter") ||
                            type.equals("agent_status_update") || type.equals("orchestrator_wait");

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
