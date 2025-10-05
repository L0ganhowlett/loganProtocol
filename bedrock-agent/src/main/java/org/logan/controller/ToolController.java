package org.logan.controller;

import org.logan.DynamicTool;
import org.logan.ToolRegistry;
import org.logan.dto.ToolRequest;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.core.document.Document;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        System.out.println("ToolController registry hash: " + System.identityHashCode(toolRegistry));
    }

    // ------------------------
    // Register new tool
    // ------------------------
    @PostMapping("/register")
    public String registerTool(@RequestBody ToolRequest request) {
        // ✅ Convert incoming schema into Document recursively
        Document schema = convertToDocument(request.getSchema());

        DynamicTool tool = new DynamicTool(
                request.getName(),
                request.getDescription(),
                schema,
                input -> {
                    System.out.println("▶ Tool invoked: " + request.getName() + " with input: " + input);
                    // Echo minimal result
                    return Document.fromMap(Map.of("status", Document.fromString("ok")));
                }
        );

        toolRegistry.register(tool);
        return "✅ Tool registered: " + tool.getName();
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
}
