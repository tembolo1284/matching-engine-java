package com.engine.types;

/**
 * Order type: Market or Limit.
 * 
 * <p>Design: Inferred from price (0 = Market, >0 = Limit).
 * Market orders execute immediately at best available price.
 * Limit orders rest in the book if not immediately matchable.
 */
public enum OrderType {
    MARKET((byte) 0),
    LIMIT((byte) 1);

    private final byte wire;

    OrderType(byte wire) {
        this.wire = wire;
    }

    /**
     * Wire format value for binary protocol.
     */
    public byte wire() {
        return wire;
    }

    /**
     * Infer order type from price.
     * 
     * <p>Convention: price=0 means market order, price>0 means limit order.
     * This matches the Rust engine behavior.
     * 
     * @param price order price in ticks
     * @return MARKET if price is 0, LIMIT otherwise
     */
    public static OrderType fromPrice(int price) {
        assert price >= 0 : "Price must be non-negative";
        return price == 0 ? MARKET : LIMIT;
    }

    /**
     * Parse from wire format.
     * 
     * @param wire byte value (0=MARKET, 1=LIMIT)
     * @return OrderType enum
     * @throws IllegalArgumentException if wire value is invalid
     */
    public static OrderType fromWire(byte wire) {
        if (wire == 0) return MARKET;
        if (wire == 1) return LIMIT;
        throw new IllegalArgumentException("Invalid order type wire value: " + wire);
    }

    /**
     * Check if this order type can rest in the book.
     * 
     * <p>Market orders are never added to the book—they either
     * fill immediately or are rejected/cancelled.
     */
    public boolean canRestInBook() {
        return this == LIMIT;
    }
}
