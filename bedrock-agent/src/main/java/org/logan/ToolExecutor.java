package org.logan;

import software.amazon.awssdk.core.document.Document;
import java.util.Map;

public class ToolExecutor {
    private final ToolRegistry toolRegistry;

    public ToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public Document run(String toolName, Map<String, Document> input) {
        DynamicTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            System.out.println("⚠️ Tool not found: " + toolName);
            return Document.fromMap(Map.of("error", Document.fromString("Tool not found")));
        }
        return tool.execute(input);
    }
}
