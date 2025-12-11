package com.engine.core;

import com.engine.types.OrderType;
import com.engine.types.Side;
import com.engine.types.Symbol;

public final class Order {
    
    private int remainingQty;
    private int price;
    private int quantity;
    private int userId;
    private int userOrderId;
    private int sideOrdinal;
    private int orderTypeOrdinal;
    private long timestampNs;
    private long symbolPacked;
    
    public Order() {}
    
    public void initialize(int userId, int userOrderId, Symbol symbol, int price,
                          int quantity, Side side, long timestampNs) {
        this.userId = userId;
        this.userOrderId = userOrderId;
        this.symbolPacked = symbol.packed();
        this.price = price;
        this.quantity = quantity;
        this.remainingQty = quantity;
        this.sideOrdinal = side.ordinal();
        this.orderTypeOrdinal = OrderType.fromPrice(price).ordinal();
        this.timestampNs = timestampNs;
    }
    
    public int remainingQty() { return remainingQty; }
    public int price() { return price; }
    public int quantity() { return quantity; }
    public int userId() { return userId; }
    public int userOrderId() { return userOrderId; }
    public Side side() { return Side.values()[sideOrdinal]; }
    public OrderType orderType() { return OrderType.values()[orderTypeOrdinal]; }
    public long timestampNs() { return timestampNs; }
    public Symbol symbol() { return Symbol.fromPacked(symbolPacked); }
    
    public boolean isFilled() { return remainingQty == 0; }
    
    public int fill(int qty) {
        int filled = Math.min(qty, remainingQty);
        remainingQty -= filled;
        return filled;
    }
    
    public boolean canMatch(int passivePrice) {
        if (orderTypeOrdinal == OrderType.MARKET.ordinal()) return true;
        return sideOrdinal == Side.BUY.ordinal() ? price >= passivePrice : price <= passivePrice;
    }
    
    public long packedKey() {
        return ((long) userId << 32) | (userOrderId & 0xFFFFFFFFL);
    }
}
