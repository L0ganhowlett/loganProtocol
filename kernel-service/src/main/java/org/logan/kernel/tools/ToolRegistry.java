package org.logan.kernel.tools;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

@Component
public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();

    public ToolRegistry(ApplicationContext context) {
        // Scan all beans
        for (Object bean : context.getBeansOfType(Object.class).values()) {
            for (Method method : bean.getClass().getMethods()) {
                if (method.isAnnotationPresent(ToolDef.class)) {
                    ToolDef def = method.getAnnotation(ToolDef.class);
                    Tool tool = new Tool(def.name(), def.description(), bean, method);
                    tools.put(def.name(), tool);
                    System.out.println("🔧 Registered tool: " + def.name());
                }
            }
        }
    }

    public Tool getTool(String name) {
        return tools.get(name);
    }

    public Collection<Tool> listTools() {
        return tools.values();
    }
}
