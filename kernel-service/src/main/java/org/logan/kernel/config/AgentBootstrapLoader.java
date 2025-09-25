package org.logan.kernel.config;

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
                    var agent = factory.createAgent(def.getId(), def.getType());
                    registry.registerAgent(agent);
                });
            }
        };
    }
}
