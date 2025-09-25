package org.logan.kernel.controller;

import org.logan.kernel.agent.AgentRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/kernel/agents")
public class AgentController {
    private final AgentRegistry registry;

    public AgentController(AgentRegistry registry) {
        this.registry = registry;
    }

    // List agents
    @GetMapping
    public ResponseEntity<List<String>> listAgents() {
        var ids = registry.listAgentIds().stream().collect(Collectors.toList());
        return ResponseEntity.ok(ids);
    }

    // Kill agent via REST (immediate)
    @DeleteMapping("/{agentId}")
    public ResponseEntity<String> killAgent(@PathVariable String agentId) {
        registry.deregisterAgent(agentId);
        return ResponseEntity.ok("Kill requested for agent: " + agentId);
    }
}
