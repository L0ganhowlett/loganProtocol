package org.logan;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.*;

@Component
public class BedrockScenario implements CommandLineRunner {
    public static final String DASHES = "-".repeat(80);

    private static final String modelId =
            "arn:aws:bedrock:ap-south-1:677276091726:inference-profile/apac.amazon.nova-lite-v1:0";

    private static String defaultPrompt = "Add 5 and 7";
    private static final int maxRecursions = 5;

    private final BedrockActions bedrockActions;
    private final ToolRegistry toolRegistry;
    private final boolean interactive = true;

    private static final String systemPrompt = """
            You are a helpful assistant. 
            Use the registered tools for all factual operations.
            - Only call tools when needed.
            - Always show reasoning briefly before result.
            - If tool fails, apologize and explain.
            - Keep answers concise and accurate.
            """;

    public BedrockScenario(BedrockActions bedrockActions, ToolRegistry toolRegistry) {
        this.bedrockActions = bedrockActions;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void run(String... args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("""
                =================================================
                Welcome to the Amazon Bedrock Dynamic Tool demo!
                =================================================
                """);
        System.out.println(DASHES);
        System.out.println(DASHES);
        System.out.println("Amazon Bedrock Converse API with Dynamic Tools demo complete.");
        System.out.println(DASHES);
    }

    private void runConversation(Scanner scanner) {
        List<Message> conversation = new ArrayList<>();
        String userInput = getUserInput("Your request:", scanner);

        while (userInput != null) {
            ContentBlock block = ContentBlock.builder().text(userInput).build();
            Message message = Message.builder()
                    .role(ConversationRole.USER)
                    .content(List.of(block))
                    .build();
            conversation.add(message);

            // Collect ALL registered tool specs
            List<ToolSpecification> toolSpecs = toolRegistry.all().stream()
                    .map(DynamicTool::toToolSpec)
                    .toList();

            ConverseResponse bedrockResponse =
                    bedrockActions.sendConverseRequestAsync(modelId, systemPrompt, conversation, toolSpecs);
            processModelResponse(bedrockResponse, conversation, maxRecursions);

            userInput = getUserInput("Your request:", scanner);
        }
    }

    private void processModelResponse(ConverseResponse modelResponse, List<Message> conversation, int maxRecursion) {
        if (maxRecursion <= 0) {
            System.out.println("⚠️ Maximum recursion depth reached.");
            return;
        }

        conversation.add(modelResponse.output().message());

        String stopReason = modelResponse.stopReasonAsString();
        if ("tool_use".equals(stopReason)) {
            handleToolUse(modelResponse.output(), conversation, maxRecursion - 1);
        } else if ("end_turn".equals(stopReason)) {
            printModelResponse(modelResponse.output().message().content().get(0).text());
            defaultPrompt = "x"; // auto-exit in non-interactive mode
        }
    }

    private void handleToolUse(ConverseOutput modelResponse, List<Message> conversation, int maxRecursion) {
        List<ContentBlock> toolResults = new ArrayList<>();

        for (ContentBlock contentBlock : modelResponse.message().content()) {
            if (contentBlock.text() != null && !contentBlock.text().isEmpty()) {
                printModelResponse(contentBlock.text());
            }

            if (contentBlock.toolUse() != null) {
                ToolResponse toolResponse = invokeTool(contentBlock.toolUse());

                ToolResultContentBlock block = ToolResultContentBlock.builder()
                        .json(toolResponse.getContent())
                        .build();

                ToolResultBlock toolResultBlock = ToolResultBlock.builder()
                        .toolUseId(toolResponse.getToolUseId())
                        .content(List.of(block))
                        .build();

                ContentBlock wrappedResult = ContentBlock.builder()
                        .toolResult(toolResultBlock)
                        .build();

                toolResults.add(wrappedResult);
            }
        }

        Message message = Message.builder()
                .role(ConversationRole.USER)
                .content(toolResults)
                .build();

        conversation.add(message);
        ConverseResponse response = sendConversationToBedrock(conversation);
        processModelResponse(response, conversation, maxRecursion);
    }

    private ToolResponse invokeTool(ToolUseBlock payload) {
        String toolName = payload.name();
        DynamicTool tool = toolRegistry.get(toolName);

        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }

        Map<String, Document> inputData = payload.input().asMap();
        System.out.println("🔧 Invoking " + toolName + " with " + inputData);

        Document result = tool.execute(inputData);

        ToolResponse response = new ToolResponse();
        response.setToolUseId(payload.toolUseId());
        response.setContent(result);
        return response;
    }

    private void printModelResponse(String message) {
        System.out.println("🤖 Model says:\n" + message + "\n");
    }

    private ConverseResponse sendConversationToBedrock(List<Message> conversation) {
        System.out.println("Calling Bedrock...");

        // ✅ collect all registered tool specs
        List<ToolSpecification> toolSpecs = toolRegistry.all().stream()
                .map(DynamicTool::toToolSpec)
                .toList();

        return bedrockActions.sendConverseRequestAsync(modelId, systemPrompt, conversation, toolSpecs);
    }

    private String getUserInput(String prompt, Scanner scanner) {
        String userInput = defaultPrompt;
        if (interactive) {
            System.out.println("*".repeat(80));
            System.out.print(prompt + " (x to exit): ");
            userInput = scanner.nextLine();
        }
        if (userInput == null || userInput.trim().isEmpty()) return getUserInput(prompt, scanner);
        if (userInput.equalsIgnoreCase("x")) return null;
        return userInput;
    }
}
