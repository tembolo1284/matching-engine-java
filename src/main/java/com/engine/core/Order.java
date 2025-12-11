package com.engine.core;

import com.engine.types.OrderType;
import com.engine.types.Side;
import com.engine.types.Symbol;

/**
 * Internal order representation used inside the order book.
 * 
 * <h2>Cache Optimization Strategy</h2>
 * <p>In Rust, we use {@code #[repr(C, align(64))]} for one order per cache line.
 * Java doesn't offer direct control over memory layout, but we optimize by:
 * <ul>
 *   <li>Field ordering: hot fields declared first (JVM often preserves declaration order)</li>
 *   <li>Primitive fields: no object references in hot path checks</li>
 *   <li>Padding fields: push object size toward 64 bytes</li>
 *   <li>Optional: Use {@code @Contended} with {@code -XX:-RestrictContended} flag</li>
 * </ul>
 * 
 * <h2>Memory Layout (Target: 64 bytes)</h2>
 * <pre>
 * JVM Object Header:     12-16 bytes (compressed oops dependent)
 * Hot fields:
 *   remainingQty:        4 bytes  (checked every match iteration)
 *   price:               4 bytes  (compared every match iteration)
 * Warm fields:
 *   quantity:            4 bytes  (original, for fill reporting)
 *   userId:              4 bytes
 *   userOrderId:         4 bytes
 * Cold fields:
 *   side:                4 bytes  (enum ordinal cached)
 *   orderType:           4 bytes  (enum ordinal cached)
 *   timestampNs:         8 bytes
 *   symbolPacked:        8 bytes  (Symbol.packed() - no reference)
 * Padding:
 *   pad0, pad1:          8 bytes  (push toward cache line)
 * </pre>
 * 
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Mutable: {@link #fill(int)} modifies remainingQty in place</li>
 *   <li>Poolable: {@link #reset()} for object reuse without allocation</li>
 *   <li>No references on hot path: side/orderType stored as ordinals</li>
 *   <li>Power of Ten Rule 5: Assertions verify invariants</li>
 * </ul>
 * 
 * <h2>Alternative: Agrona Flyweight</h2>
 * <p>For true zero-allocation and precise memory control, consider using
 * Agrona's flyweight pattern over a {@code DirectBuffer}. This class
 * provides a simpler object-oriented API for initial development.
 */
public final class Order {
    
    // =========================================================================
    // Hot Fields (accessed every match iteration)
    // =========================================================================
    
    /** Remaining unfilled quantity. Decremented on each fill. */
    private int remainingQty;
    
    /** Price in ticks. 0 = market order (though markets don't rest in book). */
    private int price;
    
    // =========================================================================
    // Warm Fields (accessed on fill)
    // =========================================================================
    
    /** Original quantity (for fill calculation). */
    private int quantity;
    
    /** User/session identifier. */
    private int userId;
    
    /** User-assigned order identifier (for cancel/fill reporting). */
    private int userOrderId;
    
    // =========================================================================
    // Cold Fields (rarely accessed after creation)
    // =========================================================================
    
    /** Side ordinal (0=BUY, 1=SELL) - avoids reference on hot path. */
    private int sideOrdinal;
    
    /** OrderType ordinal (0=MARKET, 1=LIMIT) - avoids reference on hot path. */
    private int orderTypeOrdinal;
    
    /** Timestamp in nanoseconds since epoch (for time priority). */
    private long timestampNs;
    
    /** Symbol packed as long (avoids object reference). */
    private long symbolPacked;
    
    // =========================================================================
    // Padding (push toward 64-byte cache line)
    // =========================================================================
    
    @SuppressWarnings("unused")
    private long pad0;
    
    // =========================================================================
    // Constructors
    // =========================================================================
    
    /**
     * Create a new order.
     * 
     * <p>Power of Ten Rule 5: Assertions verify quantity > 0.
     * <p>Power of Ten Rule 7: All parameters validated.
     */
    public Order(
            int userId,
            int userOrderId,
            Symbol symbol,
            int price,
            int quantity,
            Side side,
            long timestampNs) {
        
        assert userId >= 0 : "userId must be non-negative";
        assert userOrderId >= 0 : "userOrderId must be non-negative";
        assert symbol != null : "symbol cannot be null";
        assert price >= 0 : "price must be non-negative";
        assert quantity > 0 : "quantity must be positive";
        assert side != null : "side cannot be null";
        assert timestampNs >= 0 : "timestampNs must be non-negative";
        
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
    
    /**
     * Private constructor for empty/pooled orders.
     */
    private Order() {
        // All fields zero-initialized
    }
    
    /**
     * Create an empty/invalid order for pre-allocation.
     * 
     * <p>Use with object pools to avoid allocation during trading.
     * Call {@link #initialize} to set actual values.
     */
    public static Order empty() {
        return new Order();
    }
    
    /**
     * Initialize a pre-allocated order with new values.
     * 
     * <p>For use with object pools. Equivalent to creating a new Order
     * but reuses existing allocation.
     */
    public void initialize(
            int userId,
            int userOrderId,
            Symbol symbol,
            int price,
            int quantity,
            Side side,
            long timestampNs) {
        
        assert userId >= 0 : "userId must be non-negative";
        assert userOrderId >= 0 : "userOrderId must be non-negative";
        assert symbol != null : "symbol cannot be null";
        assert price >= 0 : "price must be non-negative";
        assert quantity > 0 : "quantity must be positive";
        assert side != null : "side cannot be null";
        assert timestampNs >= 0 : "timestampNs must be non-negative";
        
        this.userId = userId;
        this.userOrderId = userOrderId;
        this.symbolPacked = symbol.packed();
        this.price = price;
        this.quantity = quantity;
        this.remainingQty = quantity;
        this.sideOrdinal = side.ordinal();
        this.orderTypeOrdinal = OrderType.fromPrice(price).ordinal();
        this.timestampNs = timestampNs;
        this.pad0 = 0;
    }
    
    /**
     * Reset order to empty state for pool return.
     */
    public void reset() {
        this.remainingQty = 0;
        this.price = 0;
        this.quantity = 0;
        this.userId = 0;
        this.userOrderId = 0;
        this.sideOrdinal = 0;
        this.orderTypeOrdinal = 0;
        this.timestampNs = 0;
        this.symbolPacked = 0;
    }
    
    // =========================================================================
    // Accessors (no allocation)
    // =========================================================================
    
    public int remainingQty() {
        return remainingQty;
    }
    
    public int price() {
        return price;
    }
    
    public int quantity() {
        return quantity;
    }
    
    public int userId() {
        return userId;
    }
    
    public int userOrderId() {
        return userOrderId;
    }
    
    public Side side() {
        return Side.values()[sideOrdinal];
    }
    
    public OrderType orderType() {
        return OrderType.values()[orderTypeOrdinal];
    }
    
    public long timestampNs() {
        return timestampNs;
    }
    
    public long symbolPacked() {
        return symbolPacked;
    }
    
    /**
     * Get symbol as Symbol object.
     * 
     * <p>Note: Creates new Symbol instance. For hot path comparisons,
     * use {@link #symbolPacked()} directly.
     */
    public Symbol symbol() {
        return Symbol.fromPacked(symbolPacked);
    }
    
    // =========================================================================
    // Hot Path Accessors (inlined comparisons, no method calls)
    // =========================================================================
    
    /**
     * Check if buy side (inlined for hot path).
     */
    public boolean isBuy() {
        return sideOrdinal == 0;
    }
    
    /**
     * Check if sell side (inlined for hot path).
     */
    public boolean isSell() {
        return sideOrdinal == 1;
    }
    
    /**
     * Check if limit order (inlined for hot path).
     */
    public boolean isLimit() {
        return orderTypeOrdinal == 1;
    }
    
    /**
     * Check if market order (inlined for hot path).
     */
    public boolean isMarket() {
        return orderTypeOrdinal == 0;
    }
    
    // =========================================================================
    // Order State Methods
    // =========================================================================
    
    /**
     * Returns {@code true} if the order is fully filled.
     */
    public boolean isFilled() {
        return remainingQty == 0;
    }
    
    /**
     * Returns {@code true} if the order has unfilled quantity.
     */
    public boolean isActive() {
        return remainingQty > 0;
    }
    
    /**
     * Get filled quantity (original - remaining).
     */
    public int filledQty() {
        return quantity - remainingQty;
    }
    
    /**
     * Fill the order by up to {@code qty} units.
     * 
     * <p>Returns the quantity actually filled (≤ qty, ≤ remainingQty).
     * 
     * <p>Power of Ten Rule 5: Assertions verify invariants.
     * 
     * @param qty quantity to fill
     * @return actual quantity filled
     */
    public int fill(int qty) {
        assert qty > 0 : "fill() called with non-positive quantity: " + qty;
        assert remainingQty <= quantity 
            : "invariant violated: remainingQty > quantity";
        
        int filled = Math.min(qty, remainingQty);
        remainingQty -= filled;
        
        assert remainingQty >= 0 : "remainingQty went negative";
        assert remainingQty <= quantity : "post-fill invariant violated";
        
        return filled;
    }
    
    // =========================================================================
    // Matching Logic
    // =========================================================================
    
    /**
     * Check if this order can match against a passive order at {@code passivePrice}.
     * 
     * <ul>
     *   <li>Buy orders match if {@code passivePrice <= this.price} (or market)</li>
     *   <li>Sell orders match if {@code passivePrice >= this.price} (or market)</li>
     * </ul>
     * 
     * @param passivePrice the price of the resting order
     * @return true if this order can trade at passivePrice
     */
    public boolean canMatch(int passivePrice) {
        assert passivePrice > 0 : "passivePrice must be positive";
        
        // Market orders match anything
        if (isMarket()) {
            return true;
        }
        
        // Limit order price check
        if (isBuy()) {
            return passivePrice <= price;
        } else {
            return passivePrice >= price;
        }
    }
    
    /**
     * Pack userId and userOrderId into a single long key.
     * 
     * <p>Useful for HashMap lookup in order-to-symbol tracking.
     */
    public long packedKey() {
        return ((long) userId << 32) | (userOrderId & 0xFFFFFFFFL);
    }
    
    // =========================================================================
    // Object Methods
    // =========================================================================
    
    @Override
    public String toString() {
        // For logging only, not hot path
        return String.format(
            "Order[user=%d, orderId=%d, %s %s %s %d/%d@%d, ts=%d]",
            userId, userOrderId, symbol(), side(), orderType(),
            remainingQty, quantity, price, timestampNs);
    }
    
    /**
     * Check equality by (userId, userOrderId) - the unique order identifier.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order other)) return false;
        return userId == other.userId && userOrderId == other.userOrderId;
    }
    
    @Override
    public int hashCode() {
        return Long.hashCode(packedKey());
    }
}
