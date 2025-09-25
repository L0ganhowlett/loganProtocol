package org.logan.kernel.config;

import org.logan.kernel.agent.AgentFactory;
import org.logan.kernel.persistence.AgentEntity;
import org.logan.kernel.persistence.AgentPersistenceService;
import org.logan.kernel.agent.AgentRegistry;
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
                var agent = factory.createAgent(e.getAgentId(), e.getAgentType());
                registry.registerAgent(agent);
            }
        };
    }
}
