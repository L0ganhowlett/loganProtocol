package org.logan.kernel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.logan.protocol.MessageEnvelope;

public class SpawnerAgent implements Agent {
    private final String id;
    private final AgentRegistry registry;
    private final AgentFactory factory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String type;

    public SpawnerAgent(String id, AgentRegistry registry, AgentFactory factory,String type) {
        this.id = id;
        this.registry = registry;
        this.factory = factory;
        this.type  =type;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public void onStart() {
        System.out.println("SpawnerAgent " + id + " started.");
    }

    @Override
    public void handleMessage(MessageEnvelope<?> envelope) {
        String type = envelope.getType();
        try {
            if ("SPAWN".equalsIgnoreCase(type)) {
                SpawnRequest req = mapper.convertValue(envelope.getPayload(), SpawnRequest.class);
                String agentType = (req.getAgentType() == null || req.getAgentType().isBlank())
                        ? "NOT_SPECIFIED"
                        : req.getAgentType();
                Agent newAgent = factory.createAgent(req.getAgentId(), agentType);
                registry.registerAgent(newAgent);
                System.out.println("🪄 " + id + " spawned new agent: " + req.getAgentId());
            } else if ("AGENT_KILL".equalsIgnoreCase(type)) {
                // Payload should contain { "agentId": "..." }
                KillRequest kr = mapper.convertValue(envelope.getPayload(), KillRequest.class);
                if (kr != null && kr.getAgentId() != null) {
                    registry.deregisterAgent(kr.getAgentId());
                    System.out.println("☠️ " + id + " killed agent: " + kr.getAgentId());
                } else {
                    System.out.println("❌ AGENT_KILL payload missing agentId");
                }
            } else {
                System.out.println("📩 [" + id + "] received: " + envelope.getPayload());
            }
        } catch (Exception e) {
            System.out.println("❌ SpawnerAgent error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onStop() {
        System.out.println("SpawnerAgent " + id + " stopped.");
    }

    // DTOs
    public static class SpawnRequest {
        private String agentId;
        private String agentType;

        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }

        public String getAgentType() { return agentType; }
        public void setAgentType(String agentType) { this.agentType = agentType; }
    }

    public static class KillRequest {
        private String agentId;
        public String getAgentId() { return agentId; }
        public void setAgentId(String agentId) { this.agentId = agentId; }
    }
}
