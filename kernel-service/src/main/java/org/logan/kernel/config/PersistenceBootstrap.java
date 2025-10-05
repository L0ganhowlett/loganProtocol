package org.logan.kernel.config;

import org.logan.kernel.agent.Agent;
import org.logan.kernel.agent.AgentFactory;
import org.logan.kernel.agent.AgentRegistry;
import org.logan.kernel.persistence.AgentEntity;
import org.logan.kernel.persistence.AgentPersistenceService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PersistenceBootstrap {

    @Bean
    public CommandLineRunner loadPersistedAgents(AgentPersistenceService persistence,
                                                 AgentFactory factory,
                                                 AgentRegistry registry) {
        return args -> {
            for (AgentEntity e : persistence.loadActive()) {
                try {
                    Agent agent = factory.rehydrateAgent(
                            e.getAgentId(),
                            e.getAgentType(),
                            e.getEndpoint()
                    );
                    registry.registerAgent(agent);
                    System.out.println("♻️ Rehydrated agent: " + e.getAgentId() + " (" + e.getAgentType() + ")");
                } catch (Exception ex) {
                    System.out.println("❌ Failed to rehydrate agent " + e.getAgentId() + ": " + ex.getMessage());
                }
            }
        };
    }
}
