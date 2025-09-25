package org.logan.kernel.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String type;
    private String senderId;
    private String recipientId;
    private String correlationId;

    @Column(columnDefinition = "json")
    private String payload;

    private String signature;
    private Instant timestamp;
    private String status;
    private Instant createdAt;

    public static MessageEntity fromEnvelope(org.logan.protocol.MessageEnvelope env) {
        return MessageEntity.builder()
                .type(env.getType())
                .senderId(env.getSenderId())
                .recipientId(env.getRecipientId())
                .correlationId(env.getCorrelationId())
                .payload(env.getPayload().toString()) // Or serialize JSON
                .signature(env.getSignature())
                .timestamp(env.getTimestamp())
                .status("PENDING")
                .createdAt(Instant.now())
                .build();
    }
}
