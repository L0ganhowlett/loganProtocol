package org.logan.kernel.controller;

import org.logan.kernel.agent.Agent;
import org.logan.kernel.agent.AgentFactory;
import org.logan.kernel.agent.AgentRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/agents")
public class AgentController {

    private final AgentFactory factory;
    private final AgentRegistry registry;

    public AgentController(AgentFactory factory, AgentRegistry registry) {
        this.factory = factory;
        this.registry = registry;
    }

    // POST /agents -> spawn a new agent
    @PostMapping
    public ResponseEntity<?> createAgent(@RequestBody Map<String,String> body) {
        String type = body.get("type");
        String id = body.getOrDefault("id", null);
        if (type == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "type required"));
        }

        try {
            Agent agent = factory.createAgent(id, type);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "agentId", agent.getId(),
                    "endpoint", agent.getEndpoint()
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "message", ex.getMessage()));
        }
    }

    // DELETE /agents/{id} -> terminate an agent
    @DeleteMapping("/{agentId}")
    public ResponseEntity<?> deleteAgent(@PathVariable String agentId) {
        try {
            registry.deregisterAgent(agentId);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "message", ex.getMessage()));
        }
    }

    // GET /agents/{id} -> metadata for one agent
    @GetMapping("/{agentId}")
    public ResponseEntity<?> getAgent(@PathVariable String agentId) {
        var agent = registry.getAgent(agentId);
        if (agent == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "agentId", agent.getId(),
                "type", agent.getType(),
                "endpoint", agent.getEndpoint()
        ));
    }

    // ✅ NEW: GET /agents -> list all live agents
    // ✅ NEW: GET /agents -> list all live agents
    @GetMapping
    public ResponseEntity<?> listAgents() {
        List<Map<String, Object>> agents = registry.listAgentIds().stream()
                .map(id -> {
                    Agent agent = registry.getAgent(id);
                    return Map.<String, Object>of(
                            "agentId", agent.getId(),
                            "type", agent.getType(),
                            "endpoint", agent.getEndpoint()
                    );
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("ok", true, "agents", agents));
    }

}
