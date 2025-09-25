package org.logan.kernel.persistence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRepository extends JpaRepository<AgentEntity, String> {
    List<AgentEntity> findByStatus(String status);
}
