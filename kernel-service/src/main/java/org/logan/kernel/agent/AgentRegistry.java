package org.logan.kernel.agent;

import org.logan.kernel.persistence.AgentPersistenceService;
import org.logan.protocol.MessageEnvelope;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentRegistry {
    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    private final AgentPersistenceService persistence;

    public AgentRegistry(AgentPersistenceService persistence) {
        this.persistence = persistence;
    }

    public void registerAgent(Agent agent) {
        agents.put(agent.getId(), agent);
        try {
            agent.onStart();
        } catch (Exception e) {
            System.out.println("⚠️ agent.onStart failed for " + agent.getId() + ": " + e.getMessage());
        }
        if (persistence != null) {
            persistence.upsertActive(agent.getId(), agent.getType(), null); // null state for now
        }
        System.out.println("🟢 Registered agent: " + agent.getId());
    }

    public void deregisterAgent(String agentId) {
        Agent removed = agents.remove(agentId);
        if (removed != null) {
            try {
                removed.onStop();
            } catch (Exception e) {
                System.out.println("⚠️ agent.onStop failed for " + agentId + ": " + e.getMessage());
            }
            if (persistence != null) persistence.markTerminated(agentId);
            System.out.println("🔴 Deregistered agent: " + agentId);
        } else {
            System.out.println("⚠️ Attempted to deregister non-existent agent: " + agentId);
        }
    }

    public Agent getAgent(String agentId) {
        return agents.get(agentId);
    }

    public void routeMessage(MessageEnvelope<?> envelope) {
        Agent agent = agents.get(envelope.getRecipientId());
        if (agent != null) {
            agent.handleMessage(envelope);
        } else {
            System.out.println("⚠️ No agent found for " + envelope.getRecipientId());
        }
    }

    public Collection<String> listAgentIds() {
        return agents.keySet();
    }
}
