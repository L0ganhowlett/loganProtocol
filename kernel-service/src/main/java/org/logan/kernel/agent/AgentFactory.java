package org.logan.kernel.agent;

import org.springframework.stereotype.Component;

@Component
public class AgentFactory {
    private final AgentRegistry registry;

    public AgentFactory(AgentRegistry registry) {
        this.registry = registry;
    }

    public Agent createAgent(String id, String type) {
        return switch (type.toUpperCase()) {
            case "ECHO" -> new EchoAgent(id,type);
            case "SPAWNER" -> new SpawnerAgent(id, registry, this,type);
            default -> throw new IllegalArgumentException("Unknown agent type: " + type);
        };
    }
}
