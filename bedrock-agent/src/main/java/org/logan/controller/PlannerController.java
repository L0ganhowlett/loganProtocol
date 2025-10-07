package org.logan.controller;

import org.logan.BedrockActions;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;

import java.util.*;

/**
 * 🧠 Planner endpoint used only by the orchestrator-agent.
 * It uses the same Bedrock model to generate a structured JSON "plan" given a goal + available agents.
 */
@RestController
@RequestMapping("/chat")
public class PlannerController {

    private final BedrockActions bedrockActions;

    private static final String MODEL_ID =
            "arn:aws:bedrock:ap-south-1:677276091726:inference-profile/apac.amazon.nova-lite-v1:0";

    private static final String SYSTEM_PROMPT = """
        You are a system planner agent. 
        You receive a user goal and a list of agents with their tools.
        Your job is to return a JSON plan showing which agents to call and in what order.

        STRICT RULES:
        - Respond ONLY with JSON.
        - The response must have a field "plan": a list of steps.
        - Each step must include "agent" and "action" fields.
        - Do not include any extra text or explanations.

        Example:
        {
          "plan": [
            {"agent": "validation-agent", "action": "Validate beneficiary Adwait"},
            {"agent": "payment-agent", "action": "Pay 500 to Adwait"}
          ]
        }
    """;

    public PlannerController(BedrockActions bedrockActions) {
        this.bedrockActions = bedrockActions;
    }

    /**
     * POST /chat/planner
     * Body: { "message": "User goal with agents and tools" }
     */
    @PostMapping("/planner")
    public Map<String, Object> generatePlan(@RequestBody Map<String, Object> body) {
        try {
            String prompt = (String) body.getOrDefault("message", "Plan task.");

            List<Message> conversation = List.of(
                    Message.builder()
                            .role(ConversationRole.USER)
                            .content(List.of(ContentBlock.builder().text(prompt).build()))
                            .build()
            );

            // 🧠 Call Bedrock model with system prompt
            ConverseResponse response = bedrockActions.sendConverseRequestAsync(
                    MODEL_ID, SYSTEM_PROMPT, conversation, List.of()
            );

            String text = response.output().message().content().stream()
                    .filter(c -> c.text() != null)
                    .map(c -> c.text())
                    .reduce("", (a, b) -> a + b);

            // ✅ Parse JSON safely (Bedrock ensures plan[] structure)
            Map<String, Object> jsonResponse = new HashMap<>();
            try {
                jsonResponse = new com.fasterxml.jackson.databind.ObjectMapper().readValue(text, Map.class);
            } catch (Exception e) {
                jsonResponse.put("raw", text);
                jsonResponse.put("error", "Could not parse JSON plan, returning raw text.");
            }

            System.out.printf("🧩 [planner] Generated plan → %s%n", jsonResponse);
            return jsonResponse;

        } catch (Exception e) {
            e.printStackTrace();
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
