package org.logan.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageEnvelope<T> {
    private String type;          // e.g. GOAL, ACTION_REQUEST, EVENT
    private String senderId;      // Who sent this message
    private String recipientId;   // Target agent ID
    private String correlationId; // For request-response matching
    private T payload;            // Generic payload
    private String signature;     // Optional cryptographic signature
    private Instant timestamp;    // Time message created

    public MessageEnvelope() {
        // Default constructor for Jackson
    }

    // Convenience constructor
    public MessageEnvelope(String type, String senderId, String recipientId, T payload) {
        this.type = type;
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.payload = payload;
        this.correlationId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
    }

    // --- Getters and Setters ---

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(String recipientId) {
        this.recipientId = recipientId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public T getPayload() {
        return payload;
    }

    public void setPayload(T payload) {
        this.payload = payload;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    // --- Builder for convenience ---
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private String type;
        private String senderId;
        private String recipientId;
        private String correlationId = UUID.randomUUID().toString();
        private T payload;
        private String signature;
        private Instant timestamp = Instant.now();

        public Builder<T> type(String type) {
            this.type = type;
            return this;
        }

        public Builder<T> senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

        public Builder<T> recipientId(String recipientId) {
            this.recipientId = recipientId;
            return this;
        }

        public Builder<T> correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder<T> payload(T payload) {
            this.payload = payload;
            return this;
        }

        public Builder<T> signature(String signature) {
            this.signature = signature;
            return this;
        }

        public Builder<T> timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public MessageEnvelope<T> build() {
            MessageEnvelope<T> envelope = new MessageEnvelope<>();
            envelope.setType(type);
            envelope.setSenderId(senderId);
            envelope.setRecipientId(recipientId);
            envelope.setCorrelationId(correlationId);
            envelope.setPayload(payload);
            envelope.setSignature(signature);
            envelope.setTimestamp(timestamp);
            return envelope;
        }

    }
}
