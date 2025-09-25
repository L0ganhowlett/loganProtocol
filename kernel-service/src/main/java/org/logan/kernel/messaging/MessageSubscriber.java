package org.logan.kernel.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.logan.protocol.MessageEnvelope;
import org.logan.kernel.agent.AgentRegistry;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class MessageSubscriber implements MessageListener {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentRegistry registry;

    public MessageSubscriber(AgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            MessageEnvelope envelope = mapper.readValue(message.getBody(), MessageEnvelope.class);
            System.out.println("📩 Redis received message for agent: " + envelope.getRecipientId());
            registry.routeMessage(envelope); // forward to local agent
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
