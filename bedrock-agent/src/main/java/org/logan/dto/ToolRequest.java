package org.logan.dto;

import java.util.Map;

public class ToolRequest {
    private String name;
    private String description;
    private Map<String, Object> schema;  // JSON Schema structure
    private String type; // e.g., "calculator", "weather" etc.
    private String consumerService;
    // getters + setters

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getSchema() {
        return schema;
    }

    public void setSchema(Map<String, Object> schema) {
        this.schema = schema;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getConsumerService() {
        return consumerService;
    }

    public void setConsumerService(String consumerService) {
        this.consumerService = consumerService;
    }
}
