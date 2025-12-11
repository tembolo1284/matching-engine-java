package com.engine.types;

public enum OrderType {
    MARKET,
    LIMIT;
    
    public static OrderType fromPrice(int price) {
        return price == 0 ? MARKET : LIMIT;
    }
}
