package org.logan.controller;

import org.logan.DynamicTool;
import org.logan.ToolRegistry;
import org.logan.dto.ToolRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.core.document.Document;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;
    private final RestTemplate rest;

    public ToolController(ToolRegistry toolRegistry, RestTemplate rest) {
        this.toolRegistry = toolRegistry;
        this.rest = rest;
        System.out.println("ToolController registry hash: " + System.identityHashCode(toolRegistry));
    }

    // ------------------------
    // Register new tool
    // ------------------------
    @PostMapping("/register")
    public String registerTool(@RequestBody ToolRequest request) {
        // ✅ Convert incoming schema into AWS Document recursively
        Document schema = convertToDocument(request.getSchema());

        // 🔍 Identify the consumer’s Eureka service name
        String consumerService = request.getConsumerService();
        System.out.printf("📥 Registering tool '%s' for consumer service: %s%n",
                request.getName(), consumerService);

        // 🔧 Create a DynamicTool that delegates execution to consumer service via Eureka
        DynamicTool tool = new DynamicTool(
                request.getName(),
                request.getDescription(),
                schema,
                input -> {
                    try {
                        // Prepare input payload for consumer service
                        Map<String, Object> execRequest = Map.of(
                                "tool", request.getName(),
                                "input", input.entrySet().stream()
                                        .collect(Collectors.toMap(
                                                Map.Entry::getKey,
                                                e -> documentToPlainMap(e.getValue())
                                        ))
                        );

                        System.out.printf("🔗 Delegating execution of '%s' to consumer [%s]%n",
                                request.getName(), consumerService);

                        // ✅ Use Eureka service discovery (RestTemplate is @LoadBalanced)
                        ResponseEntity<Map> response = rest.postForEntity(
                                "http://" + consumerService + "/tools/execute",
                                new HttpEntity<>(execRequest, defaultHeaders()),
                                Map.class
                        );

                        Map<String, Object> result = response.getBody();
                        System.out.printf("✅ Tool '%s' executed successfully by %s, result=%s%n",
                                request.getName(), consumerService, result);

                        return convertToDocument(result);
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.err.printf("❌ Failed to invoke tool '%s' via %s: %s%n",
                                request.getName(), consumerService, e.getMessage());
                        return convertToDocument(Map.of(
                                "ok", false,
                                "error", e.getMessage(),
                                "consumerService", consumerService
                        ));
                    }
                }
        );

        // ✅ Register the tool into this agent’s registry
        toolRegistry.register(tool);
        System.out.printf("✅ Tool '%s' registered and bound to consumer [%s]%n",
                tool.getName(), consumerService);

        return "✅ Tool registered successfully: " + tool.getName() + " (consumer=" + consumerService + ")";
    }


    // ------------------------
    // List all tools
    // ------------------------
    @GetMapping("/list")
    public Object listTools() {
        return toolRegistry.all().stream()
                .map(tool -> Map.of(
                        "name", tool.getName(),
                        "description", tool.toToolSpec().description(),
                        "schema", documentToPlainMap(tool.toToolSpec().inputSchema().json())
                ))
                .toList();
    }

    // ------------------------
    // Execute a tool
    // ------------------------
    @PostMapping("/execute")
    public Map<String, Object> executeTool(@RequestBody Map<String, Object> body) {
        String toolName = (String) body.get("tool");
        Map<String, Object> inputRaw = (Map<String, Object>) body.get("input");

        DynamicTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            return Map.of("ok", false, "message", "Tool not found: " + toolName);
        }

        // Convert input to Document
        Map<String, Document> input = inputRaw.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> convertToDocument(e.getValue())
                ));

        Document result = tool.execute(input);

        return Map.of(
                "ok", true,
                "tool", toolName,
                "output", documentToPlainMap(result)
        );
    }

    // ------------------------
    // Helpers
    // ------------------------

    // Convert raw JSON (Map/List/values) into AWS Document
    private static Document convertToDocument(Object value) {
        if (value instanceof Map<?, ?> map) {
            return Document.fromMap(map.entrySet().stream()
                    .collect(Collectors.toMap(
                            e -> e.getKey().toString(),
                            e -> convertToDocument(e.getValue())
                    )));
        } else if (value instanceof List<?> list) {
            return Document.fromList(list.stream()
                    .map(ToolController::convertToDocument)
                    .toList());
        } else if (value instanceof String s) {
            return Document.fromString(s);
        } else if (value instanceof Number n) {
            return Document.fromNumber(n.doubleValue());
        } else if (value instanceof Boolean b) {
            return Document.fromBoolean(b);
        }
        return Document.fromNull();
    }

    // Convert Document back to plain Map/List for JSON responses
    private static Object documentToPlainMap(Document doc) {
        if (doc.isMap()) {
            Map<String, Object> out = new LinkedHashMap<>();
            doc.asMap().forEach((k, v) -> out.put(k, documentToPlainMap(v)));
            return out;
        } else if (doc.isList()) {
            return doc.asList().stream()
                    .map(ToolController::documentToPlainMap)
                    .toList();
        } else if (doc.isString()) {
            return doc.asString();
        } else if (doc.isNumber()) {
            return doc.asNumber().doubleValue();
        } else if (doc.isBoolean()) {
            return doc.asBoolean();
        }
        return null;
    }

    private HttpHeaders defaultHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
