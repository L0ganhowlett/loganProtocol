package org.logan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

public class NvidiaScenario {

    // NVIDIA API constants
    private static final String ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions";
    private static final String MODEL = "nvidia/llama-3.1-nemotron-nano-8b-v1";

    // Set this before running, e.g.
    // export NVIDIA_API_KEY="your_api_key"
    private static final String API_KEY = System.getenv("NVIDIA_API_KEY");

    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant. 
            Keep answers concise and accurate.
            Do not use internal reasoning.
            """;

    private static final Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println(" Welcome to the NVIDIA Llama 3.1 Chat Demo! ");
        System.out.println("=================================================\n");

        if (API_KEY == null || API_KEY.isEmpty()) {
            System.err.println("❌ NVIDIA_API_KEY not set. Please export it before running.");
            return;
        }

        NvidiaScenario chat = new NvidiaScenario();
        chat.startConversation();
    }

    private void startConversation() {
        List<Map<String, String>> conversation = new ArrayList<>();

        // Add system message
        conversation.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        while (true) {
            System.out.print("🧑 You: ");
            String userInput = scanner.nextLine();
            if (userInput.equalsIgnoreCase("x")) break;

            conversation.add(Map.of("role", "user", "content", userInput));

            try {
                String reply = sendToNvidia(conversation);
                System.out.println("🤖 NVIDIA Model: " + reply + "\n");
                conversation.add(Map.of("role", "assistant", "content", reply));
            } catch (Exception e) {
                System.err.println("⚠️ Error: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("\n🟢 Chat session ended.");
    }

    private String sendToNvidia(List<Map<String, String>> conversation) throws Exception {
        JSONArray messages = new JSONArray();
        for (Map<String, String> msg : conversation) {
            messages.put(new JSONObject()
                    .put("role", msg.get("role"))
                    .put("content", msg.get("content")));
        }

        JSONObject payload = new JSONObject()
                .put("model", MODEL)
                .put("messages", messages)
                .put("temperature", 0)
                .put("top_p", 0.95)
                .put("max_tokens", 2048)
                .put("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("NVIDIA API error: " + response.body());
        }

        JSONObject json = new JSONObject(response.body());
        return json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
    }
}
