package org.logan.kernel.agent;

import org.logan.protocol.MessageEnvelope;

public class EchoAgent implements Agent {
    private final String id;
    private final String type;

    public EchoAgent(String id,String type) {
        this.id = id;
        this.type = type;
    }

    @Override
    public String getId() { return id; }

    @Override
    public String getType() { return type; }

    @Override
    public void handleMessage(MessageEnvelope<?> envelope) {
        System.out.println("🤖 [" + id + "] received: " + envelope.getPayload());
    }

    @Override
    public void onStop() {
        // Example cleanup: flush state or cancel timers
        System.out.println("🤖 [" + id + "] stopping and cleaning up.");
    }
}
