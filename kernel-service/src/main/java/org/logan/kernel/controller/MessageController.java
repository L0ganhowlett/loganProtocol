package org.logan.kernel.controller;

import org.logan.kernel.agent.AgentRegistry;
import org.logan.protocol.MessageEnvelope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;

@RestController
@RequestMapping("/messages")
public class MessageController {

    private final AgentRegistry registry;

    // pending sessions keyed by sessionId
    private final ConcurrentHashMap<String, PendingSession> pendingSessions = new ConcurrentHashMap<>();

    // configuration: how long to wait for final chat_result (seconds)
    private static final long WAIT_SECONDS = 60L;

    public MessageController(AgentRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<?> postMessage(@RequestBody MessageEnvelope<?> envelope) {
        try {
            System.out.printf("📩 Message received: from=%s → to=%s type=%s%n",
                    envelope.getSenderId(), envelope.getRecipientId(), envelope.getType());

            String type = envelope.getType() == null ? "" : envelope.getType().toLowerCase();
            Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();

            // 🔹 1) Handle reasoning updates (agent_status_update)
            if ("agent_status_update".equals(type)) {
                String sessionId = extractSessionId(payload);
                String agentId = (String) payload.getOrDefault("agentId", "unknown-agent");
                PendingSession ps = pendingSessions.get(sessionId);

                Map<String, Object> event = Map.of(
                        "timestamp", new Date().toString(),
                        "type", "reasoning",
                        "phase", payload.get("phase"),
                        "message", payload.get("message"),
                        "agentId", agentId
                );

                if (ps != null) {
                    ps.addEvent(event);
                    System.out.printf("🧠 [%s] Appended reasoning (%s) to session=%s%n",
                            agentId, payload.get("phase"), sessionId);
                } else {
                    System.out.printf("⚠️ [%s] Late or orphan reasoning for session=%s%n", agentId, sessionId);
                }

                // Acknowledge to sender
                return ResponseEntity.ok(Map.of(
                        "ok", true,
                        "received", "agent_status_update",
                        "sessionId", sessionId,
                        "agentId", agentId
                ));
            }

            // 🔹 2) Final chat result from agent
            if ("chat_result".equals(type)) {
                String sessionId = extractSessionId(payload);
                String agentId = (String) payload.getOrDefault("agentId", envelope.getSenderId());
                PendingSession ps = pendingSessions.remove(sessionId);

                if (ps != null) {
                    Map<String, Object> aggregated = new LinkedHashMap<>();
                    aggregated.put("ok", true);
                    aggregated.put("from", agentId);
                    aggregated.put("sessionId", sessionId);
                    aggregated.put("events", ps.getEvents());
                    aggregated.put("result", payload);

                    ps.complete(aggregated);

                    System.out.printf("✅ [%s] Completed session=%s with final chat_result%n", agentId, sessionId);
                } else {
                    System.out.printf("⚠️ [%s] chat_result received for unknown session=%s%n", agentId, sessionId);
                }

                return ResponseEntity.ok(Map.of(
                        "ok", true,
                        "status", "chat_result_received",
                        "sessionId", sessionId,
                        "agentId", agentId
                ));
            }

            // 🔹 3) Incoming chat request from orchestrator/client
            if ("chat".equals(type)) {
                String sessionId = extractSessionId(payload);

                PendingSession ps = new PendingSession();
                pendingSessions.put(sessionId, ps);

                // Forward message to agent
                registry.routeMessage(envelope);
                System.out.printf("💬 Waiting for chat_result for session=%s (timeout %ds)%n",
                        sessionId, WAIT_SECONDS);

                Map<String, Object> result;
                try {
                    result = ps.waitForCompletion(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    pendingSessions.remove(sessionId);
                    result = Map.of(
                            "ok", false,
                            "error", "timeout waiting for chat_result",
                            "sessionId", sessionId,
                            "events", ps.getEvents()
                    );
                    System.out.printf("⏱️ Timeout waiting for chat_result for session=%s%n", sessionId);
                }

                return ResponseEntity.ok(result);
            }

            // 🔹 4) Any other message type (tool_result, etc.)
            registry.routeMessage(envelope);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "delivered", envelope.getRecipientId(),
                    "type", envelope.getType()
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(
                    Map.of("ok", false, "error", e.getMessage())
            );
        }
    }

    // --- Utility: extract sessionId safely ---
    private String extractSessionId(Map<String, Object> payload) {
        if (payload == null) return "default-session";
        Object sid = payload.get("sessionId");
        if (sid != null) return sid.toString();

        if (payload.containsKey("result") && payload.get("result") instanceof Map<?, ?>) {
            Object inner = ((Map<?, ?>) payload.get("result")).get("sessionId");
            if (inner != null) return inner.toString();
        }

        return "default-session";
    }

    // --- Inner helper: pending session state ---
    private static class PendingSession {
        private final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        private final List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<>());

        public void addEvent(Map<String, Object> event) {
            events.add(event);
        }

        public List<Map<String, Object>> getEvents() {
            return new ArrayList<>(events);
        }

        public void complete(Map<String, Object> result) {
            future.complete(result);
        }

        public Map<String, Object> waitForCompletion(long timeout, TimeUnit unit)
                throws TimeoutException, InterruptedException, ExecutionException {
            try {
                return future.get(timeout, unit);
            } catch (TimeoutException te) {
                throw te;
            }
        }
    }
}
