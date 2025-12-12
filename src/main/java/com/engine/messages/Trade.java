package com.engine.messages;

import com.engine.types.Symbol;

public record Trade(
    Symbol symbol,
    int buyUserId,
    int buyUserOrderId,
    int sellUserId,
    int sellUserOrderId,
    int price,
    int quantity
) implements OutputMessage {
    public static Trade of(Symbol symbol, int buyUserId, int buyUserOrderId,
                          int sellUserId, int sellUserOrderId, int price, int quantity) {
        return new Trade(symbol, buyUserId, buyUserOrderId, sellUserId, sellUserOrderId, price, quantity);
    }
}
