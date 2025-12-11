package com.engine.transport;

import com.engine.messages.InputMessage;

/**
 * Request from a client to the matching engine.
 * 
 * <p>Wraps the input message with client context for routing responses.
 */
public record EngineRequest(
    ClientId clientId,
    int userId,
    InputMessage message
) {
    
    /**
     * Create a request with user ID extracted from message.
     */
    public static EngineRequest of(ClientId clientId, InputMessage message) {
        int userId = extractUserId(message);
        return new EngineRequest(clientId, userId, message);
    }
    
    /**
     * Extract user ID from an input message for routing.
     */
    private static int extractUserId(InputMessage msg) {
        return switch (msg) {
            case com.engine.messages.NewOrder order -> order.userId();
            case com.engine.messages.Cancel cancel -> cancel.userId();
            default -> 0;
        };
    }
}
