package com.engine.messages;

import com.engine.types.Side;
import com.engine.types.Symbol;

public sealed interface OutputMessage permits Ack, CancelAck, Trade, TopOfBookUpdate {
    Symbol symbol();
}

// All must be public to be accessed from other packages!

public record Ack(Symbol symbol, int userId, int userOrderId) implements OutputMessage {
    public static Ack of(Symbol symbol, int userId, int userOrderId) {
        return new Ack(symbol, userId, userOrderId);
    }
}

public record CancelAck(Symbol symbol, int userId, int userOrderId) implements OutputMessage {
    public static CancelAck of(Symbol symbol, int userId, int userOrderId) {
        return new CancelAck(symbol, userId, userOrderId);
    }
}

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

public record TopOfBookUpdate(Symbol symbol, Side side, int price, int quantity) implements OutputMessage {
    public boolean eliminated() { return quantity == 0; }
    
    public static TopOfBookUpdate of(Symbol symbol, Side side, int price, int quantity) {
        return new TopOfBookUpdate(symbol, side, price, quantity);
    }
    
    public static TopOfBookUpdate eliminated(Symbol symbol, Side side) {
        return new TopOfBookUpdate(symbol, side, 0, 0);
    }
}
