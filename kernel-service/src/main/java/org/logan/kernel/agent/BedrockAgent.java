package org.logan.kernel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.logan.protocol.MessageEnvelope;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

public class BedrockAgent implements Agent {
    private final String id;
    private final String endpoint;   // e.g., http://localhost:9001
    private final Process process;   // spawned process, or null if rehydrated
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public BedrockAgent(String id, String endpoint, Process process) {
        this.id = id;
        this.endpoint = endpoint;
        this.process = process;
    }

    @Override
    public String getId() { return id; }

    @Override
    public String getType() { return "BEDROCK"; }

    @Override
    public void handleMessage(MessageEnvelope<?> envelope) {
        try {
            System.out.printf("📩 [%s] Received message from=%s type=%s%n",
                    id, envelope.getSenderId(), envelope.getType());

            String type = envelope.getType().toLowerCase();

            switch (type) {
                case "tool_request" -> handleToolRequest(envelope);
                case "chat" -> handleChat(envelope);
                default -> System.out.println("⚠️ [" + id + "] Unknown message type: " + type);
            }

        } catch (Exception e) {
            System.err.println("❌ [" + id + "] Error handling message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handles incoming tool_request messages.
     */
    private void handleToolRequest(MessageEnvelope<?> envelope) throws Exception {
        Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();
        String tool = (String) payload.get("tool");
        Object input = payload.get("input");
        String sessionId = (String) payload.getOrDefault("sessionId", "default-session");

        // Log what's happening
        System.out.printf("🧰 [%s] Executing tool '%s' with input=%s%n", id, tool, input);

        // Forward to this agent’s /tools/execute endpoint
        String url = endpoint + "/tools/execute";
        String json = objectMapper.writeValueAsString(Map.of(
                "tool", tool,
                "input", input
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.printf("✅ [%s] Tool '%s' executed → %s%n", id, tool, resp.body());

        // Wrap tool result with metadata for the kernel
        Map<String, Object> toolResponse = new HashMap<>(objectMapper.readValue(resp.body(), Map.class));
        toolResponse.put("toolUseId", tool); // optional consistency
        toolResponse.put("sessionId", sessionId);

        // Send result back to kernel as tool_result
        MessageEnvelope<Object> resultEnvelope = new MessageEnvelope<>();
        resultEnvelope.setSenderId(this.id);
        resultEnvelope.setRecipientId(envelope.getSenderId());
        resultEnvelope.setType("tool_result");
        resultEnvelope.setPayload(toolResponse);


        sendToKernel(resultEnvelope);
    }

    /**
     * Handles incoming chat-type messages.
     * This will forward the chat payload to the agent’s /chat/{sessionId} endpoint,
     * which will invoke the LLM (BedrockActions) and handle reasoning.
     */
    private void handleChat(MessageEnvelope<?> envelope) {
        try {
            System.out.printf("💬 [%s] Chat message received: %s%n", id, envelope.getPayload());

            Map<String, Object> payload = (Map<String, Object>) envelope.getPayload();
            String sessionId = (String) payload.getOrDefault("sessionId", "default-session");
            String message = (String) payload.get("message");

            if (message == null || message.isEmpty()) {
                System.out.printf("⚠️ [%s] Chat payload missing 'message' field%n", id);
                return;
            }

            // ✅ Include agentId dynamically in request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("message", message);
            requestBody.put("agentId", this.id); // 👈 pass the actual agent id (e.g., echo-1)

            String url = endpoint + "/chat/" + sessionId;
            String json = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            System.out.printf("🌐 [%s] Forwarding chat to %s with body=%s%n", id, url, json);

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            Map<String, Object> llmResponse = objectMapper.readValue(resp.body(), Map.class);

            // 🧠 Ensure agentId is included in final response payload for kernel visibility
            llmResponse.put("agentId", this.id);
            llmResponse.putIfAbsent("sessionId", sessionId);   // ✅ add sessionId
            String stopReason = (String) llmResponse.get("stopReason");
            boolean isFinal = Boolean.TRUE.equals(llmResponse.get("isFinal"))
                    || "end_turn".equalsIgnoreCase(stopReason);
            llmResponse.put("isFinal", isFinal);

            System.out.printf("🤖 [%s] Chat LLM response → stopReason=%s | isFinal=%s%n",
                    id, stopReason, isFinal);

            // ✅ Always send chat_result (kernel will handle aggregation)
            MessageEnvelope<Object> responseEnvelope = new MessageEnvelope<>();
            responseEnvelope.setSenderId(this.id);               // this agent (e.g., echo-1)
            responseEnvelope.setRecipientId(envelope.getSenderId()); // kernel or orchestrator
            responseEnvelope.setType("chat_result");
            responseEnvelope.setPayload(llmResponse);

            sendToKernel(responseEnvelope);
            System.out.printf("📤 [%s] Sent chat_result → kernel (isFinal=%s)%n", id, isFinal);

        } catch (Exception e) {
            System.err.printf("❌ [%s] Error processing chat: %s%n", id, e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * Central method for sending messages back to Kernel.
     */
    private void sendToKernel(MessageEnvelope<?> message) {
        try {
            String kernelUrl = System.getProperty("kernel.url", "http://localhost:8080/messages");

            String payload = objectMapper.writeValueAsString(message);
            HttpRequest kernelRequest = HttpRequest.newBuilder()
                    .uri(URI.create(kernelUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            httpClient.sendAsync(kernelRequest, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        System.out.printf("📤 [%s] Sent message → Kernel response: %s%n", id, resp.body());
                    })
                    .exceptionally(ex -> {
                        System.err.printf("⚠️ [%s] Failed to send to kernel: %s%n", id, ex.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            System.err.printf("❌ [%s] Error sending message to kernel: %s%n", id, e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onStart() {
        System.out.println("▶ BedrockAgent " + id + " is live at " + endpoint);
    }

    @Override
    public void onStop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            System.out.println("🛑 BedrockAgent " + id + " stopped.");
        } else {
            System.out.println("ℹ️ BedrockAgent " + id + " rehydrated only, no process to stop.");
        }
    }



    public String getEndpoint() { return endpoint; }
}
