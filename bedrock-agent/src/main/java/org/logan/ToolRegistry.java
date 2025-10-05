package org.logan;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;

import java.util.*;

@Component
public class ToolRegistry {
    private final Map<String, DynamicTool> tools = new HashMap<>();

    public void register(DynamicTool tool) {
        tools.put(tool.getName(), tool);
    }

    public DynamicTool get(String name) {
        return tools.get(name);
    }

    public Optional<DynamicTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<DynamicTool> all() {
        return tools.values();
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    public Object execute(String toolName, Object rawInput) {
        DynamicTool tool = tools.get(toolName);
        if (tool == null) throw new IllegalArgumentException("Unknown tool: " + toolName);

        if (!(rawInput instanceof Map<?,?> rawMap)) {
            throw new IllegalArgumentException("Tool input must be a map");
        }

        Map<String, Document> inputDocs = new HashMap<>();
        rawMap.forEach((k, v) -> inputDocs.put(k.toString(), convertToDocument(v)));

        return tool.execute(inputDocs);
    }

    private Document convertToDocument(Object v) {
        if (v == null) return Document.fromNull();
        if (v instanceof String s) return Document.fromString(s);
        if (v instanceof Number n) return Document.fromNumber(n.doubleValue());
        if (v instanceof Boolean b) return Document.fromBoolean(b);
        if (v instanceof Map<?, ?> m) {
            Map<String, Document> map = new HashMap<>();
            m.forEach((k, val) -> map.put(k.toString(), convertToDocument(val)));
            return Document.fromMap(map);
        }
        if (v instanceof List<?> list) {
            return Document.fromList(list.stream().map(this::convertToDocument).toList());
        }
        throw new IllegalArgumentException("Unsupported type: " + v.getClass());
    }
}
