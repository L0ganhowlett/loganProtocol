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

    // POST /agents -> spawn a new agent (existing)
    @PostMapping
    public ResponseEntity<?> createAgent(@RequestBody Map<String,String> body) {
        String type = body.get("type");
        String id = body.getOrDefault("id", null);
        if (type == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "type required"));
        }
        try {
            Agent agent = factory.createAgent(id, type);
            return ResponseEntity.ok(Map.of("ok", true, "agentId", agent.getId(), "endpoint", agent.getEndpoint()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    // NEW: spawn orchestrator (id defaults to orchestrator-agent)
    @PostMapping("/spawn-orchestrator")
    public ResponseEntity<?> spawnOrchestrator(@RequestBody(required = false) Map<String,String> body) {
        String id = body == null ? null : body.getOrDefault("id", "orchestrator-agent");
        String type = "BEDROCK";
        try {
            Agent agent = factory.createAgent(id, type);
            // Optionally call onStart - AgentFactory should have started process and AgentRegistry should have it registered
            return ResponseEntity.ok(Map.of("ok", true, "agentId", agent.getId(), "endpoint", agent.getEndpoint()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    // GET /agents -> list (existing)
    @GetMapping
    public ResponseEntity<?> listAgents() {
        List<Map<String,Object>> agents = registry.listAgentIds().stream()
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
