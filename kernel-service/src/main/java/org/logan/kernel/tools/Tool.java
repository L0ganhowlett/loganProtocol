package org.logan.kernel.tools;

import java.lang.reflect.Method;
import java.util.Map;

public class Tool {
    private final String name;
    private final String description;
    private final Object target;
    private final Method method;

    public Tool(String name, String description, Object target, Method method) {
        this.name = name;
        this.description = description;
        this.target = target;
        this.method = method;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }

    public Object execute(Map<String, Object> args) throws Exception {
        // simplistic: assumes single Map argument
        return method.invoke(target, args);
    }
}
