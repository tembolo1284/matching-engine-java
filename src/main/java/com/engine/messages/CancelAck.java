package com.engine.messages;

import com.engine.types.Symbol;

public record CancelAck(Symbol symbol, int userId, int userOrderId) implements OutputMessage {
    public static CancelAck of(Symbol symbol, int userId, int userOrderId) {
        return new CancelAck(symbol, userId, userOrderId);
    }
}
