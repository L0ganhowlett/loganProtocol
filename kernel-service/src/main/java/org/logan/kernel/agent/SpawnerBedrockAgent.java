package org.logan.kernel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.logan.protocol.MessageEnvelope;

public class SpawnerBedrockAgent extends BedrockAgent {
    private final AgentRegistry registry;
    private final AgentFactory factory;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpawnerBedrockAgent(String id, String endpoint, Process process,
                               AgentRegistry registry, AgentFactory factory) {
        super(id, endpoint, process);
        this.registry = registry;
        this.factory = factory;
    }

    @Override
    public String getType() {
        return "BEDROCK_SPAWNER";
    }

    @Override
    public void handleMessage(MessageEnvelope<?> envelope) {
        String type = envelope.getType();
        try {
            if ("SPAWN".equalsIgnoreCase(type)) {
                SpawnRequest req = mapper.convertValue(envelope.getPayload(), SpawnRequest.class);
                String agentType = (req.getAgentType() == null || req.getAgentType().isBlank())
                        ? "BEDROCK"
                        : req.getAgentType();

                Agent newAgent = factory.createAgent(req.getAgentId(), agentType);
                registry.registerAgent(newAgent);

                System.out.println("🪄 Spawner " + getId() + " spawned new agent: " + req.getAgentId());

            } else if ("AGENT_KILL".equalsIgnoreCase(type)) {
                KillRequest kr = mapper.convertValue(envelope.getPayload(), KillRequest.class);
                if (kr != null && kr.getAgentId() != null) {
                    registry.deregisterAgent(kr.getAgentId());
                    System.out.println("☠️ Spawner " + getId() + " killed agent: " + kr.getAgentId());
                } else {
                    System.out.println("❌ AGENT_KILL payload missing agentId");
                }

            } else if ("SELF_TERMINATE".equalsIgnoreCase(type)) {
                registry.deregisterAgent(getId());
                System.out.println("💣 Spawner " + getId() + " terminated itself.");

            } else {
                // Fallback: delegate to normal Bedrock behavior (HTTP forward)
                super.handleMessage(envelope);
            }
        } catch (Exception e) {
            System.out.println("❌ SpawnerBedrockAgent error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // DTOs for spawn/kill
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
