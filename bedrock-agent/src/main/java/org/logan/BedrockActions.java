package org.logan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class BedrockActions {

    @Value("${aws.bedrock.credentials.accessKeyId}")
    private String accessKey;

    @Value("${aws.bedrock.credentials.secretAccessKey}")
    private String secretKey;

    @Value("${aws.bedrock.region}")
    private String region;

    private volatile BedrockRuntimeAsyncClient bedrockRuntimeClient;

    private BedrockRuntimeAsyncClient getClient() {
        if (bedrockRuntimeClient == null) {
            SdkAsyncHttpClient httpClient = NettyNioAsyncHttpClient.builder()
                    .maxConcurrency(50)
                    .connectionTimeout(Duration.ofSeconds(60))
                    .readTimeout(Duration.ofSeconds(60))
                    .writeTimeout(Duration.ofSeconds(60))
                    .build();

            ClientOverrideConfiguration overrideConfig = ClientOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofMinutes(2))
                    .apiCallAttemptTimeout(Duration.ofSeconds(90))
                    .build();

            bedrockRuntimeClient = BedrockRuntimeAsyncClient.builder()
                    .region(Region.of(region))
                    .httpClient(httpClient)
                    .credentialsProvider(
                            StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(accessKey, secretKey)
                            )
                    )
                    .overrideConfiguration(overrideConfig)
                    .build();
        }
        return bedrockRuntimeClient;
    }

    /**
     * Sends an asynchronous converse request with multiple tools.
     *
     * Supports both Amazon Nova and Anthropic Claude models:
     * - Nova expects the system prompt as a message with role=system.
     * - Anthropic expects the system prompt in the `.system()` field.
     */
    public ConverseResponse sendConverseRequestAsync(
            String modelId,
            String systemPrompt,
            List<Message> conversation,
            List<ToolSpecification> toolSpecs
    ) {
        ConverseRequest.Builder requestBuilder = ConverseRequest.builder()
                .modelId(modelId);


        if (toolSpecs != null && !toolSpecs.isEmpty()) {
            ToolConfiguration configuration = ToolConfiguration.builder()
                    .tools(toolSpecs.stream().map(t -> Tool.builder().toolSpec(t).build()).toList())
                    .build();
            requestBuilder.toolConfig(configuration);
        }



        // ✅ Nova: system prompt as first message
        Message systemMessage = Message.builder()
                .role(ConversationRole.USER)
                .content(List.of(ContentBlock.builder().text(systemPrompt).build()))
                .build();

        List<Message> allMessages = new ArrayList<>();
        allMessages.add(systemMessage);
        allMessages.addAll(conversation);

        requestBuilder = requestBuilder.messages(allMessages);


        try {
            return getClient().converse(requestBuilder.build()).join();
        } catch (ModelNotReadyException ex) {
            throw new RuntimeException("Model is not ready: " + ex.getMessage(), ex);
        } catch (BedrockRuntimeException ex) {
            throw new RuntimeException("Failed to converse with Bedrock model: " + ex.getMessage(), ex);
        }
    }
}
