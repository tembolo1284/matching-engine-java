package com.engine.messages;

import com.engine.types.Side;
import com.engine.types.Symbol;

public record TopOfBookUpdate(Symbol symbol, Side side, int price, int quantity) implements OutputMessage {
    public boolean eliminated() { return quantity == 0; }
    
    public static TopOfBookUpdate of(Symbol symbol, Side side, int price, int quantity) {
        return new TopOfBookUpdate(symbol, side, price, quantity);
    }
    
    public static TopOfBookUpdate eliminated(Symbol symbol, Side side) {
        return new TopOfBookUpdate(symbol, side, 0, 0);
    }
}
