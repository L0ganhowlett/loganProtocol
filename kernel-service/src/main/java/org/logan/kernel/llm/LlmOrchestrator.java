package org.logan.kernel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiService;
import com.theokanning.openai.completion.chat.*;

import org.logan.kernel.tools.Tool;
import org.logan.kernel.tools.ToolRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Component
public class LlmOrchestrator {
    private final ToolRegistry registry;
    private final OpenAiService openai;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmOrchestrator(ToolRegistry registry) {
        this.registry = registry;
        // API key should be in env var OPENAI_API_KEY
        this.openai = new OpenAiService(System.getenv("OPENAI_API_KEY"), Duration.ofSeconds(30));
    }

    public Object handleQuery(String userQuery) throws Exception {
        Collection<Tool> tools = registry.listTools();

        // 1️⃣ Build function/tool schema
        List<ChatFunction> functions = new ArrayList<>();
        for (Tool t : tools) {
            ChatFunction f = ChatFunction.builder()
                    .name(t.getName())
                    .description(t.getDescription())
                    .parameters(ChatFunctionParameters.builder()
                            .type("object")
                            .properties(Map.of(
                                    "id", Map.of("type", "string"),
                                    "amount", Map.of("type", "string")
                            ))
                            .required(List.of("id"))
                            .build())
                    .build();
            functions.add(f);
        }

        // 2️⃣ Send to OpenAI
        ChatCompletionRequest req = ChatCompletionRequest.builder()
                .model("gpt-4o-mini")  // Or "gpt-4o"
                .messages(List.of(new ChatMessage("user", userQuery)))
                .functions(functions)
                .functionCall("auto")
                .build();

        ChatCompletionResult result = openai.createChatCompletion(req);

        ChatMessage response = result.getChoices().get(0).getMessage();

        // 3️⃣ Check if tool was called
        if (response.getFunctionCall() != null) {
            String toolName = response.getFunctionCall().getName();
            String argsJson = response.getFunctionCall().getArguments();

            Tool tool = registry.getTool(toolName);
            if (tool == null) {
                return "🤖 LLM tried to call unknown tool: " + toolName;
            }

            Map<String, Object> args = mapper.readValue(argsJson, Map.class);
            Object output = tool.execute(args);

            return "🔧 Tool `" + toolName + "` executed → " + output;
        }

        // 4️⃣ No tool, just return raw response
        return response.getContent();
    }
}
