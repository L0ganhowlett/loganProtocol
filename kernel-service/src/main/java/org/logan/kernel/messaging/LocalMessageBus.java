package org.logan.kernel.messaging;

import org.logan.kernel.agent.AgentRegistry;
import org.logan.protocol.MessageEnvelope;
import org.springframework.stereotype.Component;

@Component
public class LocalMessageBus implements MessageBus{
    private final AgentRegistry registry;

    public LocalMessageBus(AgentRegistry registry) {
        this.registry = registry;
    }

    public void send(MessageEnvelope envelope) {
        var recipient = registry.getAgent(envelope.getRecipientId());
        if (recipient != null) {
            recipient.handleMessage(envelope);
        } else {
            System.out.println("⚠️ Local agent not found: " + envelope.getRecipientId());
        }
    }
}
