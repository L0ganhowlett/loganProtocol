package org.logan.kernel.controller;

import org.logan.protocol.MessageEnvelope;
import org.logan.kernel.messaging.MessageBus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/kernel")
public class SendController {
    private final MessageBus bus;

    public SendController(MessageBus bus) {
        this.bus = bus;
    }

    @PostMapping("/send")
    public String sendMessage(@RequestBody MessageEnvelope envelope) {
        bus.send(envelope);   // ✅ Uses LocalMessageBus under the hood
        return "Sent to " + envelope.getRecipientId();
    }
}
