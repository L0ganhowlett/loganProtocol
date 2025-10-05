package org.logan.kernel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "")
public class AgentConfigProperties {

    private List<AgentDefinition> agents;

    public List<AgentDefinition> getAgents() {
        return agents;
    }

    public void setAgents(List<AgentDefinition> agents) {
        this.agents = agents;
    }

    public static class AgentDefinition {
        private String id;
        private String type;
        private String endpoint; // NEW

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    }
}
