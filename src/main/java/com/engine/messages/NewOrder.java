package com.engine.messages;

import com.engine.types.OrderType;
import com.engine.types.Side;
import com.engine.types.Symbol;

/**
 * New order submission message.
 * 
 * <p>Design Principles:
 * <ul>
 *   <li>Immutable record: thread-safe, value semantics</li>
 *   <li>All fields are primitives or value types (no heap references on hot path)</li>
 *   <li>Order type inferred from price (0 = market, >0 = limit)</li>
 *   <li>Compact: 24 bytes of payload (4+4+8+4+4 = userId, userOrderId, symbol, price, qty)</li>
 * </ul>
 * 
 * <p>Wire Format (CSV): {@code N, userId, symbol, quantity, price, side, userOrderId}
 * <p>Wire Format (Binary): {@code [type:1][userId:4][userOrderId:4][symbol:8][price:4][qty:4][side:1]}
 */
public record NewOrder(
    int userId,
    int userOrderId,
    Symbol symbol,
    int price,
    int quantity,
    Side side
) implements InputMessage {
    
    /**
     * Canonical constructor with validation.
     * 
     * <p>Power of Ten Rule 5: Assertions for invariants.
     * <p>Power of Ten Rule 7: Validate all parameters.
     */
    public NewOrder {
        assert userId >= 0 : "userId must be non-negative";
        assert userOrderId >= 0 : "userOrderId must be non-negative";
        assert symbol != null : "symbol cannot be null";
        assert price >= 0 : "price must be non-negative";
        assert quantity > 0 : "quantity must be positive";
        assert side != null : "side cannot be null";
    }
    
    /**
     * Static factory method for clearer call sites.
     */
    public static NewOrder of(
            int userId,
            int userOrderId,
            Symbol symbol,
            int price,
            int quantity,
            Side side) {
        return new NewOrder(userId, userOrderId, symbol, price, quantity, side);
    }
    
    /**
     * Convenience factory with string symbol.
     * 
     * <p>Note: Allocates Symbol. Use {@link #of(int, int, Symbol, int, int, Side)} 
     * with pre-allocated Symbol on hot path.
     */
    public static NewOrder of(
            int userId,
            int userOrderId,
            String symbol,
            int price,
            int quantity,
            Side side) {
        return new NewOrder(userId, userOrderId, Symbol.of(symbol), price, quantity, side);
    }
    
    @Override
    public Type type() {
        return Type.NEW_ORDER;
    }
    
    /**
     * Infer order type from price.
     * 
     * <p>Convention: price=0 means market order.
     */
    public OrderType orderType() {
        return OrderType.fromPrice(price);
    }
    
    /**
     * Check if this is a market order.
     */
    public boolean isMarket() {
        return price == 0;
    }
    
    /**
     * Check if this is a limit order.
     */
    public boolean isLimit() {
        return price > 0;
    }
    
    /**
     * Check if this is a buy order.
     */
    public boolean isBuy() {
        return side == Side.BUY;
    }
    
    /**
     * Check if this is a sell order.
     */
    public boolean isSell() {
        return side == Side.SELL;
    }
    
    @Override
    public String toString() {
        // For logging only, not hot path
        return String.format("NewOrder[user=%d, orderId=%d, %s %s %d@%d]",
            userId, userOrderId, side, symbol, quantity, price);
    }
}
