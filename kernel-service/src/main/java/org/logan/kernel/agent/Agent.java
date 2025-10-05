package org.logan.kernel.agent;

import org.logan.protocol.MessageEnvelope;

public interface Agent {
    String getId();
    String getType();
    String getEndpoint();  // ✅ required so persistence/registry can use it

    void handleMessage(MessageEnvelope<?> envelope);

    default void onStart() {}
    default void onStop() {}
}
