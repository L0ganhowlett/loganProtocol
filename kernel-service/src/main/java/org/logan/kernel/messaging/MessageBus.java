package org.logan.kernel.messaging;

import org.logan.protocol.MessageEnvelope;

public interface MessageBus {
    void send(MessageEnvelope envelope);
}
