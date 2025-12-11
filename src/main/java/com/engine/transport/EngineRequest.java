package com.engine.transport;

import com.engine.messages.Cancel;
import com.engine.messages.InputMessage;
import com.engine.messages.NewOrder;

public record EngineRequest(ClientId clientId, int userId, InputMessage message) {
    
    public static EngineRequest of(ClientId clientId, InputMessage message) {
        int userId = switch (message) {
            case NewOrder o -> o.userId();
            case Cancel c -> c.userId();
            default -> 0;
        };
        return new EngineRequest(clientId, userId, message);
    }
}
