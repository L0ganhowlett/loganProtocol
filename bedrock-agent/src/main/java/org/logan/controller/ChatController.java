package org.logan.controller;

import org.logan.BedrockActions;
import org.logan.DynamicTool;
import org.logan.ToolRegistry;
import org.logan.protocol.MessageEnvelope;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private static final String modelId =
            "arn:aws:bedrock:ap-south-1:677276091726:inference-profile/apac.amazon.nova-lite-v1:0";

    private static final int maxRecursions = 5;

    private static final String systemPrompt = """
            You are a helpful assistant.
            Use the registered tools for all factual operations.
            - Only call tools when needed.
            - Always show reasoning briefly before result.
            - If tool fails, apologize and explain.
            - Keep answers concise and accurate.
            """;

    private final BedrockActions bedrockActions;
    private final ToolRegistry toolRegistry;
    private final RestTemplate restTemplate = new RestTemplate();

    // 🧠 Chat sessions + reasoning + audit log
    private final Map<String, List<Message>> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, String>>> reasoningHistory = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> chatAudit = new ConcurrentHashMap<>();

    private final String kernelBaseUrl;

    public ChatController(
            BedrockActions bedrockActions,
            ToolRegistry toolRegistry,
            @org.springframework.beans.factory.annotation.Value("${kernel.base-url}") String kernelBaseUrl
    ) {
        this.bedrockActions = bedrockActions;
        this.toolRegistry = toolRegistry;
        this.kernelBaseUrl = kernelBaseUrl;
        System.out.println("🧠 ChatController initialized with ToolRegistry hash: " +
                System.identityHashCode(toolRegistry));
    }

    // 🔹 Entry point for chat (now receives agentId dynamically)
    @PostMapping("/{sessionId}")
    public Map<String, Object> chat(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body
    ) {
        String userInput = (String) body.get("message");
        String agentId = (String) body.getOrDefault("agentId", "chat-agent"); // ✅ sent from BedrockAgent

        List<Message> conversation = sessions.computeIfAbsent(sessionId, k -> new ArrayList<>());

        // Add user input
        conversation.add(Message.builder()
                .role(ConversationRole.USER)
                .content(List.of(ContentBlock.builder().text(userInput).build()))
                .build());

        // 🧾 Log input
        chatAudit.computeIfAbsent(sessionId, k -> new ArrayList<>())
                .add(Map.of(
                        "timestamp", new Date().toString(),
                        "type", "user_input",
                        "agentId", agentId,
                        "message", userInput
                ));

        List<Map<String, Object>> collectedEvents = new ArrayList<>();
        ConverseResponse response = sendConversationToBedrock(conversation);

        processModelResponse(sessionId, agentId, response, conversation, maxRecursions, collectedEvents);

        // ✅ Final payload includes agentId
        return Map.of(
                "stopReason", response.stopReasonAsString(),
                "events", collectedEvents,
                "audit", chatAudit.getOrDefault(sessionId, List.of()),
                "agentId", agentId,
                "sessionId", sessionId,
                "isFinal", "end_turn".equals(response.stopReasonAsString())
        );
    }

    // 🧾 History
    @GetMapping("/{sessionId}/history")
    public List<Map<String, String>> getSessionHistory(@PathVariable String sessionId) {
        return reasoningHistory.getOrDefault(sessionId, List.of());
    }

    // 🧾 Audit
    @GetMapping("/{sessionId}/audit")
    public List<Map<String, Object>> getAudit(@PathVariable String sessionId) {
        return chatAudit.getOrDefault(sessionId, List.of());
    }

    // 🔹 Handles model responses
    private void processModelResponse(
            String sessionId,
            String agentId,
            ConverseResponse modelResponse,
            List<Message> conversation,
            int maxRecursion,
            List<Map<String, Object>> collectedEvents
    ) {
        if (maxRecursion <= 0) {
            collectedEvents.add(Map.of("type", "system", "message", "⚠️ Maximum recursion depth reached."));
            return;
        }

        conversation.add(modelResponse.output().message());
        String stopReason = modelResponse.stopReasonAsString();

        if ("tool_use".equals(stopReason)) {
            handleToolUse(sessionId, agentId, modelResponse.output(), conversation, maxRecursion - 1, collectedEvents);
        } else if ("end_turn".equals(stopReason)) {
            modelResponse.output().message().content().forEach(c -> {
                if (c.text() != null) {
                    collectedEvents.add(Map.of("type", "model", "message", c.text()));
                    sendReasoningUpdate(sessionId, agentId, "thinking", c.text());
                }
            });
        }
    }

    private void handleToolUse(
            String sessionId,
            String agentId,
            ConverseOutput modelResponse,
            List<Message> conversation,
            int maxRecursion,
            List<Map<String, Object>> collectedEvents
    ) {
        List<ContentBlock> toolResults = new ArrayList<>();

        for (ContentBlock contentBlock : modelResponse.message().content()) {

            if (contentBlock.text() != null && !contentBlock.text().isEmpty()) {
                collectedEvents.add(Map.of("type", "model", "message", contentBlock.text()));
                sendReasoningUpdate(sessionId, agentId, "thinking", contentBlock.text());
            }

            if (contentBlock.toolUse() != null) {
                ToolUseBlock useBlock = contentBlock.toolUse();

                try {
                    String toolName = useBlock.name();
                    Map<String, Document> inputData = useBlock.input().asMap();

                    // 🧾 Local audit + send to kernel
                    chatAudit.computeIfAbsent(sessionId, k -> new ArrayList<>())
                            .add(Map.of(
                                    "timestamp", new Date().toString(),
                                    "type", "tool_invocation",
                                    "tool", toolName,
                                    "input", inputData,
                                    "agentId", agentId
                            ));
                    sendToolEventToKernel(sessionId, agentId, "tool_invocation", toolName, Map.of("input", inputData));

                    ToolResponse toolResponse = invokeTool(useBlock);

                    chatAudit.get(sessionId).add(Map.of(
                            "timestamp", new Date().toString(),
                            "type", "tool_result",
                            "tool", toolName,
                            "output", toolResponse.getContent(),
                            "agentId", agentId
                    ));
                    sendToolEventToKernel(sessionId, agentId, "tool_result", toolName, Map.of("output", toolResponse.getContent()));

                    ToolResultContentBlock resultBlock = ToolResultContentBlock.builder()
                            .json(toolResponse.getContent())
                            .build();

                    ToolResultBlock toolResultBlock = ToolResultBlock.builder()
                            .toolUseId(toolResponse.getToolUseId())
                            .content(List.of(resultBlock))
                            .build();

                    ContentBlock wrapped = ContentBlock.builder()
                            .toolResult(toolResultBlock)
                            .build();

                    toolResults.add(wrapped);
                    sendReasoningUpdate(sessionId, agentId, "tool_success", "Executed local tool: " + toolName);

                } catch (Exception e) {
                    chatAudit.get(sessionId).add(Map.of(
                            "timestamp", new Date().toString(),
                            "type", "error",
                            "message", e.getMessage(),
                            "agentId", agentId
                    ));
                    sendReasoningUpdate(sessionId, agentId, "tool_error", "Tool execution failed: " + e.getMessage());
                    collectedEvents.add(Map.of("type", "error", "message", e.getMessage()));
                }
            }
        }

        if (!toolResults.isEmpty()) {
            Message toolMessage = Message.builder()
                    .role(ConversationRole.USER)
                    .content(toolResults)
                    .build();

            conversation.add(toolMessage);
            ConverseResponse nextResponse = sendConversationToBedrock(conversation);
            processModelResponse(sessionId, agentId, nextResponse, conversation, maxRecursion, collectedEvents);
        }
    }

    private ToolResponse invokeTool(ToolUseBlock payload) {
        String toolName = payload.name();
        DynamicTool tool = toolRegistry.get(toolName);

        if (tool == null) throw new IllegalArgumentException("Unknown tool: " + toolName);

        Map<String, Document> inputData = payload.input().asMap();
        System.out.println("🔧 Invoking " + toolName + " with " + inputData);

        Document result = tool.execute(inputData);

        ToolResponse response = new ToolResponse();
        response.setToolUseId(payload.toolUseId());
        response.setContent(result);
        return response;
    }

    private ConverseResponse sendConversationToBedrock(List<Message> conversation) {
        List<ToolSpecification> toolSpecs = toolRegistry.all().stream()
                .map(DynamicTool::toToolSpec)
                .toList();

        System.out.println("🧩 Sending " + toolSpecs.size() + " registered tool(s) to Bedrock");

        return bedrockActions.sendConverseRequestAsync(
                modelId, systemPrompt, conversation, toolSpecs
        );
    }

    private void sendReasoningUpdate(String sessionId, String agentId, String phase, String message) {
        try {
            System.out.println("🧩 Reasoning update → agent=" + agentId + " | phase=" + phase + " | " + message);

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
        } catch (Exception e) {
            System.err.println("⚠️ Failed to send reasoning update: " + e.getMessage());
        }
    }

    // 🧰 New helper — sends tool_invocation/tool_result events to kernel
    private void sendToolEventToKernel(String sessionId, String agentId, String type, String toolName, Map<String, Object> payloadData) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("agentId", agentId);
            payload.put("tool", toolName);
            payload.putAll(payloadData);

            MessageEnvelope<Map<String, Object>> env = new MessageEnvelope<>();
            env.setSenderId(agentId);
            env.setRecipientId("kernel");
            env.setType(type);
            env.setPayload(payload);

            restTemplate.postForEntity(kernelBaseUrl + "/messages", env, Void.class);
            System.out.printf("🧰 [%s] Sent %s for tool=%s session=%s%n", agentId, type, toolName, sessionId);
        } catch (Exception e) {
            System.err.printf("⚠️ [%s] Failed to send %s: %s%n", agentId, type, e.getMessage());
        }
    }

    private static class ToolResponse {
        private String toolUseId;
        private Document content;

        public String getToolUseId() { return toolUseId; }
        public void setToolUseId(String toolUseId) { this.toolUseId = toolUseId; }

        public Document getContent() { return content; }
        public void setContent(Document content) { this.content = content; }
    }
}
