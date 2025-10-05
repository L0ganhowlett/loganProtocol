package org.logan.kernel.persistence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class AgentPersistenceService {
    private final AgentRepository repo;

    public AgentPersistenceService(AgentRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void upsertActive(String agentId, String agentType, String stateJson, String endpoint) {
        AgentEntity entity = repo.findById(agentId).orElseGet(AgentEntity::new);
        entity.setAgentId(agentId);
        entity.setAgentType(agentType);
        entity.setState(stateJson);
        entity.setStatus("ACTIVE");
        entity.setEndpoint(endpoint); // ✅ persist endpoint
        repo.save(entity);
    }

    @Transactional
    public void markTerminated(String agentId) {
        repo.findById(agentId).ifPresent(e -> {
            e.setStatus("TERMINATED");
            repo.save(e);
        });
    }

    public List<AgentEntity> loadActive() {
        return repo.findByStatus("ACTIVE");
    }
}
