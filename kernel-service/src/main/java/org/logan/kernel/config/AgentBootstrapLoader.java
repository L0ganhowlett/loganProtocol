package org.logan.kernel.config;

import org.logan.kernel.agent.Agent;
import org.logan.kernel.agent.AgentFactory;
import org.logan.kernel.agent.AgentRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentBootstrapLoader {

    @Bean
    public CommandLineRunner initAgents(AgentConfigProperties props,
                                        AgentFactory factory,
                                        AgentRegistry registry) {
        return args -> {
            if (props.getAgents() != null) {
                props.getAgents().forEach(def -> {
                    try {
                        Agent agent;
                        if (def.getEndpoint() != null &&
                                ("BEDROCK".equalsIgnoreCase(def.getType()) ||
                                        "BEDROCK_SPAWNER".equalsIgnoreCase(def.getType()))) {
                            // ✅ rehydrate if endpoint provided
                            agent = factory.rehydrateAgent(def.getId(), def.getType(), def.getEndpoint());
                        } else {
                            // ✅ otherwise spawn new
                            agent = factory.createAgent(def.getId(), def.getType());
                        }
                        registry.registerAgent(agent);
                        System.out.println("🚀 Bootstrapped agent: " + def.getId() + " (" + def.getType() + ")");
                    } catch (Exception e) {
                        System.out.println("❌ Failed to bootstrap agent " + def.getId() + ": " + e.getMessage());
                    }
                });
            }
        };
    }
}
