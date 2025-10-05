-- ==============================
-- V1__init.sql
-- Initial schema for Agentic Kernel (MySQL)
-- ==============================

-- ========== AGENTS ==========
CREATE TABLE IF NOT EXISTS agents (
    agent_id    VARCHAR(100) PRIMARY KEY,
    agent_type  VARCHAR(50) NOT NULL,
    state       JSON,                      -- MySQL supports JSON, not JSONB
    status      VARCHAR(20) NOT NULL,      -- ACTIVE, TERMINATED
    endpoint    VARCHAR(255),              -- where the agent service is reachable
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- ========== MESSAGES ==========
CREATE TABLE IF NOT EXISTS messages (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    type           VARCHAR(50) NOT NULL,          -- GOAL, ACTION_REQUEST, EVENT, etc.
    sender_id      VARCHAR(100) NOT NULL,
    recipient_id   VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    payload        JSON,                          -- flexible for LLM / tool results
    signature      VARCHAR(512) NULL,
    timestamp      TIMESTAMP NOT NULL,
    status         VARCHAR(20) DEFAULT 'PENDING', -- PENDING, DELIVERED, FAILED
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ========== GOALS ==========
CREATE TABLE IF NOT EXISTS goals (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id       VARCHAR(100) NOT NULL,
    description    TEXT NOT NULL,
    status         VARCHAR(20) DEFAULT 'ACTIVE',   -- ACTIVE, COMPLETED, FAILED
    correlation_id VARCHAR(100) NOT NULL,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
