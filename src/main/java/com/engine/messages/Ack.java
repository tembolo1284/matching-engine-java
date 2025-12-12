package com.engine.messages;

import com.engine.types.Symbol;

public record Ack(Symbol symbol, int userId, int userOrderId) implements OutputMessage {
    public static Ack of(Symbol symbol, int userId, int userOrderId) {
        return new Ack(symbol, userId, userOrderId);
    }
}
