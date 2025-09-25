package org.logan.kernel.persistence;


import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agents")
public class AgentEntity {
    @Id
    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(name = "agent_type", nullable = false)
    private String agentType;

    @Column(name = "state", columnDefinition = "jsonb")
    private String state;

    @Column(name = "status", nullable = false)
    private String status; // ACTIVE, TERMINATED

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    // getters & setters

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
