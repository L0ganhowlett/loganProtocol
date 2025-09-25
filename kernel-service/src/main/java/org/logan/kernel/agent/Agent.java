package org.logan.kernel.agent;

import org.logan.protocol.MessageEnvelope;

public interface Agent {
    String getId();

    String getType();

    void handleMessage(MessageEnvelope<?> envelope);

    /**
     * Called when the agent is registered and ready.
     */
    default void onStart() {}

    /**
     * Called when the agent is being terminated - do cleanup here.
     */
    default void onStop() {}
}
