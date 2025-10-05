package org.logan;

import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

import java.util.Map;
import java.util.function.Function;

public class DynamicTool {
    private final String name;
    private final String description;
    private final Document schema; // JSON schema as Document
    private final Function<Map<String, Document>, Document> executor;

    public DynamicTool(String name,
                       String description,
                       Document schema,
                       Function<Map<String, Document>, Document> executor) {
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.executor = executor;
    }

    public String getName() {
        return name;
    }

    public ToolSpecification toToolSpec() {
        return ToolSpecification.builder()
                .name(name)
                .description(description)
                .inputSchema(ToolInputSchema.builder().json(schema).build())
                .build();
    }

    public Document execute(Map<String, Document> input) {
        return executor.apply(input);
    }
}
