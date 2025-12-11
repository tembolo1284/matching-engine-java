package com.engine.messages;

import com.engine.types.OrderType;
import com.engine.types.Side;
import com.engine.types.Symbol;

public record NewOrder(
    int userId,
    int userOrderId,
    Symbol symbol,
    int price,
    int quantity,
    Side side
) implements InputMessage {
    
    public OrderType orderType() {
        return OrderType.fromPrice(price);
    }
    
    public long packedKey() {
        return packKey(userId, userOrderId);
    }
    
    public static long packKey(int userId, int userOrderId) {
        return ((long) userId << 32) | (userOrderId & 0xFFFFFFFFL);
    }
}
