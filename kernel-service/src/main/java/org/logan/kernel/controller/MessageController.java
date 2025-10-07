package org.logan.kernel.controller;

import jakarta.annotation.PostConstruct;
import org.logan.kernel.agent.AgentRegistry;
import org.logan.protocol.MessageEnvelope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/messages")
public class MessageController {

    private final AgentRegistry registry;
    private final ConcurrentHashMap<String, PendingSession> pendingSessions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CompletableFuture<Map<String, Object>>>> agentWaiters = new ConcurrentHashMap<>();
    private final Map<String, SseEmitter> activeEmitters = new ConcurrentHashMap<>();

    private static final ExecutorService MSG_EXECUTOR = Executors.newCachedThreadPool();
    private static final long WAIT_SECONDS = 60L;

    public MessageController(AgentRegistry registry) {
        this.registry = registry;
    }

    // --- SSE Stream Endpoint ---
    @GetMapping("/stream")
    public SseEmitter streamEvents() {
        SseEmitter emitter = new SseEmitter(0L);
        String emitterId = UUID.randomUUID().toString();
        activeEmitters.put(emitterId, emitter);

        System.out.println("🔌 Client connected to /messages/stream: " + emitterId);

        emitter.onCompletion(() -> {
            activeEmitters.remove(emitterId);
            System.out.println("✅ SSE completed: " + emitterId);
        });
        emitter.onTimeout(() -> {
            activeEmitters.remove(emitterId);
            emitter.complete();
            System.out.println("⚠️ SSE timeout: " + emitterId);
        });
        emitter.onError((e) -> {
            System.err.println("❌ SSE error: " + e.getMessage());
            activeEmitters.remove(emitterId);
        });

        return emitter;
    }

    // --- Periodic Heartbeat ---
    @PostConstruct
    public void startSseHeartbeat() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            Map<String, Object> heartbeat = Map.of(
                    "timestamp", new Date().toString(),
                    "type", "heartbeat",
                    "message", "Orchestrator is listening"
            );
            broadcastSse(heartbeat);
        }, 60, 60, TimeUnit.SECONDS);
    }

    // --- Handle Incoming Messages ---
    @PostMapping
    public ResponseEntity<?> postMessage(@RequestBody MessageEnvelope<?> envelope) {
        try {
            System.out.printf("📩 Message received: from=%s → to=%s type=%s%n",
                    envelope.getSenderId(), envelope.getRecipientId(), envelope.getType());

            String type = (envelope.getType() == null ? "" : envelope.getType().toLowerCase());
            Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();

            switch (type) {
                // 🧠 Reasoning
                case "agent_status_update" -> {
                    String sessionId = extractSessionId(payload);
                    String agentId = (String) payload.getOrDefault("agentId", envelope.getSenderId());
                    PendingSession ps = pendingSessions.computeIfAbsent(sessionId, k -> new PendingSession());

                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("timestamp", new Date().toString());
                    event.put("type", "reasoning");
                    event.put("phase", payload.get("phase"));
                    event.put("message", payload.get("message"));
                    event.put("agentId", agentId);
                    ps.addEvent(event);
                    ps.addAudit(event);
                    broadcastSse(event);

                    System.out.printf("🧠 [%s] Reasoning (%s) logged for session=%s%n",
                            agentId, payload.get("phase"), sessionId);
                    return ResponseEntity.ok(Map.of(
                            "ok", true,
                            "received", "agent_status_update",
                            "sessionId", sessionId,
                            "agentId", agentId
                    ));
                }

                // 📋 Register agent plan
                case "register_agent_plan" -> {
                    String targetAgent = (String) payload.get("targetAgent");
                    String sessionId = (String) payload.get("sessionId");
                    String fromAgent = (String) payload.getOrDefault("fromAgent", envelope.getSenderId());

                    if (targetAgent == null)
                        return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Missing targetAgent"));

                    PendingSession ps = pendingSessions.computeIfAbsent(sessionId, k -> new PendingSession());
                    ps.registerAgent(targetAgent);
                    Map<String, Object> event = Map.of(
                            "timestamp", new Date().toString(),
                            "type", "register_agent_plan",
                            "fromAgent", fromAgent,
                            "targetAgent", targetAgent,
                            "sessionId", sessionId
                    );
//                    broadcastSse(event);

                    System.out.printf("📋 Registered %s in session=%s%n", targetAgent, sessionId);
                    return ResponseEntity.ok(Map.of("ok", true));
                }

                // 🤝 Delegation
                case "delegation" -> {
                    String targetAgent = (String) payload.get("targetAgent");
                    String sessionId = (String) payload.get("sessionId");
                    String msg = (String) payload.get("message");
                    String fromAgent = (String) payload.getOrDefault("fromAgent", envelope.getSenderId());

                    if (targetAgent == null)
                        return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Missing targetAgent"));

                    PendingSession ps = pendingSessions.computeIfAbsent(sessionId, k -> new PendingSession());
                    ps.registerAgent(targetAgent);

                    Map<String, Object> event = Map.of(
                            "timestamp", new Date().toString(),
                            "type", "delegation",
                            "agentId", fromAgent,
                            "targetAgent", targetAgent,
                            "message", msg,
                            "sessionId", sessionId
                    );
                    ps.addAudit(event);
                    broadcastSse(event);

                    MSG_EXECUTOR.submit(() -> {
                        try {
                            if (registry.hasAgent(targetAgent)) {
                                MessageEnvelope<Map<String, Object>> delegatedChat = new MessageEnvelope<>();
                                delegatedChat.setSenderId(fromAgent);
                                delegatedChat.setRecipientId(targetAgent);
                                delegatedChat.setType("chat");
                                delegatedChat.setPayload(Map.of("sessionId", sessionId, "message", msg));
                                registry.routeMessage(delegatedChat);
                            }
                        } catch (Exception e) {
                            System.err.printf("❌ [async] Delegation failed for %s → %s: %s%n",
                                    fromAgent, targetAgent, e.getMessage());
                        }
                    });

                    return ResponseEntity.ok(Map.of(
                            "ok", true,
                            "status", "delegation_executed",
                            "targetAgent", targetAgent,
                            "sessionId", sessionId
                    ));
                }

                // 🧰 Tool events
                case "tool_invocation", "tool_result" -> {
                    String sessionId = extractSessionId(payload);
                    String agentId = (String) payload.getOrDefault("agentId", envelope.getSenderId());
                    PendingSession ps = pendingSessions.computeIfAbsent(sessionId, k -> new PendingSession());

                    Map<String, Object> event = new LinkedHashMap<>(payload);
                    event.put("timestamp", new Date().toString());
                    event.put("type", type);
                    event.put("agentId", agentId);
                    event.put("message","Executing tool: "+payload.get("tool"));
                    ps.addAudit(event);
//                    broadcastSse(event);

                    System.out.printf("🧰 [%s] %s recorded for session=%s%n", agentId, type, sessionId);
                    return ResponseEntity.ok(Map.of("ok", true));
                }

                // 💬 User chat
                case "chat" -> {
                    String sessionId = extractSessionId(payload);
                    PendingSession ps = new PendingSession();
                    pendingSessions.put(sessionId, ps);

                    String sender = envelope.getSenderId() != null ? envelope.getSenderId() : "user";

                    Map<String, Object> event = Map.of(
                            "timestamp", new Date().toString(),
                            "type", "user_input",
                            "agentId", sender,
                            "message", payload.get("message"),
                            "sessionId", sessionId
                    );
                    ps.addAudit(event);
                    broadcastSse(event);

                    registry.routeMessage(envelope);
                    System.out.printf("💬 Waiting for chat_result (session=%s, timeout=%ds)%n", sessionId, WAIT_SECONDS);

                    Map<String, Object> result;
                    try {
                        result = ps.waitForCompletion(WAIT_SECONDS, TimeUnit.SECONDS);
                    } catch (TimeoutException te) {
                        pendingSessions.remove(sessionId);
                        result = Map.of(
                                "ok", false,
                                "error", "timeout waiting for chat_result",
                                "sessionId", sessionId,
                                "events", ps.getEvents(),
                                "audit", ps.getAudit()
                        );
                    }
                    return ResponseEntity.ok(result);
                }

                // 🏁 Chat results
                case "chat_result" -> {
                    String sessionId = extractSessionId(payload);
                    String agentId = (String) payload.getOrDefault("agentId", envelope.getSenderId());
                    String recipient = envelope.getRecipientId() != null ? envelope.getRecipientId() : "";

                    String sender = envelope.getSenderId() != null ? envelope.getSenderId() : agentId;
                    if (sender != null && sender.equalsIgnoreCase(recipient)) {
                        System.out.printf("🚫 Skipped forwarding chat_result loopback (%s → %s)%n", sender, recipient);
                        return ResponseEntity.ok(Map.of("ok", true, "skipped", true));
                    }

                    PendingSession ps = pendingSessions.get(sessionId);
                    if (ps == null) {
                        System.out.printf("⚠️ [%s] chat_result for unknown session=%s%n", agentId, sessionId);
                        return ResponseEntity.ok(Map.of("ok", false, "unknown_session", sessionId));
                    }

                    ps.markAgentCompleted(agentId);
                    completeAgentWaiter(sessionId, agentId, payload);

                    // 🔁 Route back to orchestrator if not already orchestrator
                    if (!"orchestrator-agent".equalsIgnoreCase(agentId)) {
                        try {
                            MessageEnvelope<Map<String, Object>> orchestratorMsg = new MessageEnvelope<>();
                            orchestratorMsg.setSenderId(agentId);
                            orchestratorMsg.setRecipientId("orchestrator-agent");
                            orchestratorMsg.setType("chat_result");
                            orchestratorMsg.setPayload(payload);

                            if (registry.hasAgent("orchestrator-agent")) {
                                registry.routeMessage(orchestratorMsg);
                                System.out.printf("🔁 Routed chat_result of %s → orchestrator-agent (session=%s)%n",
                                        agentId, sessionId);
                            } else {
                                System.err.println("⚠️ orchestrator-agent not found in registry");
                            }
                        } catch (Exception e) {
                            System.err.printf("⚠️ Failed routing back to orchestrator-agent: %s%n", e.getMessage());
                        }
                    }

                    ps.addAudit(Map.of(
                            "timestamp", new Date().toString(),
                            "type", "chat_result",
                            "agentId", agentId,
                            "status", "completed",
                            "sessionId", sessionId
                    ));
                    broadcastSse(Map.of(
                            "timestamp", new Date().toString(),
                            "type", "chat_result",
                            "agentId", agentId,
                            "status", "completed",
                            "sessionId", sessionId,
                            "message", String.format("✅ [%s] Completed session=%s (agents left=%d)",
                                    agentId, sessionId, ps.getRemainingAgents().size())
                    ));


                    System.out.printf("✅ [%s] Completed session=%s (agents left=%d)%n",
                            agentId, sessionId, ps.getRemainingAgents().size());

                    // ✅ Only finalize session if the orchestrator sends final chat_result
                    if ("orchestrator-agent".equalsIgnoreCase(agentId)) {
                        pendingSessions.remove(sessionId);
                        Map<String, Object> aggregated = new LinkedHashMap<>();
                        aggregated.put("ok", true);
                        aggregated.put("sessionId", sessionId);
                        aggregated.put("message", ps.getEvents());
                        aggregated.put("audit", ps.getAudit());
                        aggregated.put("completedAgents", ps.getCompletedAgents());
                        aggregated.put("result", payload);
                        ps.complete(aggregated);
                        broadcastSse(aggregated);
                        System.out.printf("🏁 Orchestrator finalized session=%s%n", sessionId);
                    }

                    return ResponseEntity.ok(Map.of(
                            "ok", true,
                            "status", "chat_result_received",
                            "sessionId", sessionId,
                            "agentId", agentId
                    ));
                }

                default -> {
                    registry.routeMessage(envelope);
                    return ResponseEntity.ok(Map.of("ok", true, "type", type));
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    // --- Waiter registration ---
    @PostMapping("/register-waiter")
    public ResponseEntity<?> registerWaiter(@RequestBody Map<String, Object> payload) {
        try {
            String sessionId = (String) payload.get("sessionId");
            String agentId = (String) payload.get("agentId");

            if (sessionId == null || agentId == null)
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Missing sessionId or agentId"));

            CompletableFuture<Map<String, Object>> waiter = new CompletableFuture<>();
            registerAgentWaiter(sessionId, agentId, waiter);

            System.out.printf("📡 Registered waiter for session=%s agent=%s%n", sessionId, agentId);
            return ResponseEntity.ok(Map.of("ok", true, "sessionId", sessionId, "agentId", agentId));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    // --- SSE Broadcast ---
    private void broadcastSse(Map<String, Object> event) {
        List<String> dead = new ArrayList<>();
        activeEmitters.forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name("message").data(event));
            } catch (Exception e) {
                System.out.println("⚠️ [SSE] Removing dead emitter: " + id);
                dead.add(id);
            }
        });
        dead.forEach(activeEmitters::remove);
    }

    // --- Utility Methods ---
    private String extractSessionId(Map<String, Object> payload) {
        if (payload == null) return "default-session";
        Object sid = payload.get("sessionId");
        if (sid != null) return sid.toString();
        if (payload.containsKey("result") && payload.get("result") instanceof Map<?, ?> inner)
            return Optional.ofNullable(((Map<?, ?>) inner).get("sessionId")).map(Object::toString).orElse("default-session");
        return "default-session";
    }

    // --- Async orchestration helpers ---
    public void registerAgentWaiter(String sessionId, String agentId, CompletableFuture<Map<String, Object>> future) {
        agentWaiters.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
                .put(agentId, future);
    }

    public void completeAgentWaiter(String sessionId, String agentId, Map<String, Object> result) {
        Optional.ofNullable(agentWaiters.get(sessionId))
                .map(m -> m.remove(agentId))
                .ifPresent(fut -> fut.complete(result));
    }

    // --- Pending Session Inner Class ---
    private static class PendingSession {
        private final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        private final List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<>());
        private final List<Map<String, Object>> audit = Collections.synchronizedList(new ArrayList<>());
        private final Set<String> activeAgents = ConcurrentHashMap.newKeySet();
        private final Set<String> completedAgents = ConcurrentHashMap.newKeySet();

        public void registerAgent(String agentId) {
            if (agentId != null) activeAgents.add(agentId);
        }

        public void markAgentCompleted(String agentId) {
            completedAgents.add(agentId);
            activeAgents.remove(agentId);
        }

        public boolean allAgentsCompleted() {
            return activeAgents.isEmpty();
        }

        public Set<String> getRemainingAgents() { return new HashSet<>(activeAgents); }
        public Set<String> getCompletedAgents() { return new HashSet<>(completedAgents); }
        public void addEvent(Map<String, Object> event) { events.add(event); }
        public void addAudit(Map<String, Object> event) { audit.add(event); }

        public List<Map<String, Object>> getEvents() { return new ArrayList<>(events); }
        public List<Map<String, Object>> getAudit() { return new ArrayList<>(audit); }

        public void complete(Map<String, Object> result) { future.complete(result); }

        public Map<String, Object> waitForCompletion(long timeout, TimeUnit unit)
                throws TimeoutException, InterruptedException, ExecutionException {
            return future.get(timeout, unit);
        }
    }
}
