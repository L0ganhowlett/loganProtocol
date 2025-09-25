package org.logan.kernel.messaging;

import org.logan.protocol.MessageEnvelope;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class MessagePublisher {
    private final RedisTemplate<String, Object> redisTemplate;

    public MessagePublisher(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publish(String recipientAgentId, MessageEnvelope envelope) {
        String channel = "agent:" + recipientAgentId;
        redisTemplate.convertAndSend(channel, envelope);
    }
}

