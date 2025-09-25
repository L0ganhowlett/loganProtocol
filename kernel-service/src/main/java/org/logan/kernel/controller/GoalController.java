package org.logan.kernel.controller;

import org.logan.protocol.MessageEnvelope;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/kernel")
public class GoalController {

    @PostMapping("/goal")
    public String receiveGoal(@RequestBody MessageEnvelope<Object> envelope) {
        System.out.println("🎯 Received GOAL from " + envelope.getSenderId() + ": " + envelope.getPayload());
        return "✅ Goal accepted with correlationId=" + envelope.getCorrelationId();
    }
}
