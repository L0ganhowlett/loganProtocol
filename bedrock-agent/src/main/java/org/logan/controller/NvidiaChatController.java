package org.logan.controller;

import org.json.JSONArray;
import org.json.JSONObject;
import org.logan.DynamicTool;
import org.logan.ToolRegistry;
import org.logan.protocol.MessageEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NVIDIA version of ChatController — uses NVIDIA Llama 3.1 Nemotron Nano 8B
 * via OpenAI-compatible endpoint.
 */
@RestController
@RequestMapping("/chat")
public class NvidiaChatController {

    // NVIDIA constants
    private static final String ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions";
    private static final String MODEL = "nvidia/llama-3.1-nemotron-nano-8b-v1";
    private static final String API_KEY = System.getenv("NVIDIA_API_KEY");

    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant.
            Keep answers concise and accurate.
            You can describe how you'd use tools, but you cannot directly call them.
            """;

    private final ToolRegistry toolRegistry;
    private final RestTemplate restTemplate = new RestTemplate();

    private final Map<String, List<Map<String, String>>> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> reasoningHistory = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> chatAudit = new ConcurrentHashMap<>();

    private final String kernelBaseUrl;

    public NvidiaChatController(
            ToolRegistry toolRegistry,
            @Value("${kernel.base-url}") String kernelBaseUrl
    ) {
        this.toolRegistry = toolRegistry;
        this.kernelBaseUrl = kernelBaseUrl;
        System.out.println("🧠 NvidiaChatController initialized with ToolRegistry hash: " +
                System.identityHashCode(toolRegistry));
    }

    // 🔹 Entry point for chat (session-based)
    @PostMapping("/{sessionId}")
    public Map<String, Object> chat(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body
    ) {
        String userInput = (String) body.get("message");
        String agentId = (String) body.getOrDefault("agentId", "nvidia-agent");

        List<Map<String, String>> conversation = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());

        // Add system prompt once
        if (conversation.isEmpty()) {
            conversation.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        }

        // Add user message
        conversation.add(Map.of("role", "user", "content", userInput));

        chatAudit.computeIfAbsent(sessionId, k -> new ArrayList<>())
                .add(Map.of(
                        "timestamp", new Date().toString(),
                        "type", "user_input",
                        "agentId", agentId,
                        "message", userInput
                ));

        try {
            String modelReply = sendConversationToNvidia(conversation);

            // Add assistant reply to conversation
            conversation.add(Map.of("role", "assistant", "content", modelReply));

            chatAudit.get(sessionId).add(Map.of(
                    "timestamp", new Date().toString(),
                    "type", "model_output",
                    "agentId", agentId,
                    "message", modelReply
            ));

            sendReasoningUpdate(sessionId, agentId, "response", modelReply);

            return Map.of(
                    "sessionId", sessionId,
                    "agentId", agentId,
                    "message", modelReply,
                    "audit", chatAudit.get(sessionId),
                    "stopReason", "end_turn",
                    "isFinal", true
            );

        } catch (Exception e) {
            e.printStackTrace();
            return Map.of(
                    "error", e.getMessage(),
                    "sessionId", sessionId,
                    "agentId", agentId
            );
        }
    }

    // 🧾 Reasoning and audit endpoints
    @GetMapping("/{sessionId}/history")
    public List<Map<String, String>> getSessionHistory(@PathVariable String sessionId) {
        return reasoningHistory.getOrDefault(sessionId, List.of());
    }

    @GetMapping("/{sessionId}/audit")
    public List<Map<String, Object>> getAudit(@PathVariable String sessionId) {
        return chatAudit.getOrDefault(sessionId, List.of());
    }

    // 🧠 Core NVIDIA API call
    private String sendConversationToNvidia(List<Map<String, String>> conversation) throws Exception {
        JSONArray messages = new JSONArray();
        for (Map<String, String> msg : conversation) {
            messages.put(new JSONObject()
                    .put("role", msg.get("role"))
                    .put("content", msg.get("content")));
        }

        JSONObject payload = new JSONObject()
                .put("model", MODEL)
                .put("messages", messages)
                .put("temperature", 0)
                .put("top_p", 0.95)
                .put("max_tokens", 2048)
                .put("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("NVIDIA API error: " + response.body());
        }

        JSONObject json = new JSONObject(response.body());
        return json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
    }

    // 🧩 Send reasoning/status update to kernel
    private void sendReasoningUpdate(String sessionId, String agentId, String phase, String message) {
        try {
            reasoningHistory.computeIfAbsent(sessionId, k -> new ArrayList<>())
                    .add(Map.of("phase", phase, "message", message));

            chatAudit.computeIfAbsent(sessionId, k -> new ArrayList<>())
                    .add(Map.of(
                            "timestamp", new Date().toString(),
                            "type", "reasoning",
                            "phase", phase,
                            "message", message,
                            "agentId", agentId
                    ));

            MessageEnvelope<Map<String, Object>> envelope = new MessageEnvelope<>();
            envelope.setSenderId(agentId);
            envelope.setRecipientId("kernel");
            envelope.setType("agent_status_update");
            envelope.setPayload(Map.of(
                    "sessionId", sessionId,
                    "phase", phase,
                    "message", message,
                    "agentId", agentId
            ));

            restTemplate.postForEntity(kernelBaseUrl + "/messages", envelope, Void.class);
            System.out.printf("🧩 Reasoning → [%s] %s%n", agentId, message);
        } catch (Exception e) {
            System.err.println("⚠️ Failed to send reasoning update: " + e.getMessage());
        }
    }
}
