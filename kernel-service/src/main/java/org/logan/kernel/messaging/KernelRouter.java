package org.logan.kernel.messaging;

import org.logan.kernel.agent.AgentRegistry;
import org.logan.kernel.persistence.MessageEntity;
import org.logan.kernel.persistence.MessageRepository;
import org.logan.protocol.MessageEnvelope;
import org.springframework.stereotype.Component;

@Component
public class KernelRouter {
    private final MessageRepository messageRepository;
    private final LocalMessageBus localBus;
    private final MessagePublisher publisher;
    private final AgentRegistry registry;

    public KernelRouter(MessageRepository repo,
                        LocalMessageBus localBus,
                        MessagePublisher publisher,
                        AgentRegistry registry) {
        this.messageRepository = repo;
        this.localBus = localBus;
        this.publisher = publisher;
        this.registry = registry;
    }

    public void route(MessageEnvelope envelope) {
        // 1. Save in DB
        MessageEntity entity = MessageEntity.fromEnvelope(envelope);
        messageRepository.save(entity);

        // 2. Route locally if recipient exists
        if (registry.getAgent(envelope.getRecipientId()) != null) {
            localBus.send(envelope);
            entity.setStatus("DELIVERED");
            messageRepository.save(entity);
        } else {
            // 3. Else publish over Redis
            publisher.publish(envelope.getRecipientId(), envelope);
        }
    }
}
