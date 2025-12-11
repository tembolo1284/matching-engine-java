package com.engine.transport;

import com.engine.messages.Cancel;
import com.engine.messages.InputMessage;
import com.engine.messages.NewOrder;

/**
 * Engine request with client context.
 */
public record EngineRequest(
    ClientId clientId,
    int userId,
    InputMessage message
) {
    
    /**
     * Create request, extracting userId from message.
     */
    public static EngineRequest of(ClientId clientId, InputMessage message) {
        int userId = extractUserId(message);
        return new EngineRequest(clientId, userId, message);
    }
    
    private static int extractUserId(InputMessage message) {
        if (message instanceof NewOrder order) {
            return order.userId();
        } else if (message instanceof Cancel cancel) {
            return cancel.userId();
        } else {
            return 0;
        }
    }
}
