package com.engine.messages;

import com.engine.types.Side;
import com.engine.types.Symbol;

/**
 * Output message from the matching engine.
 * 
 * <p>Design Principles:
 * <ul>
 *   <li>Sealed interface: exhaustive pattern matching in switch</li>
 *   <li>Every variant includes Symbol for stateless downstream routing</li>
 *   <li>All implementations are immutable value types</li>
 *   <li>Supports efficient routing: Acks to originator, Trades to both parties</li>
 * </ul>
 * 
 * <p>Message Types:
 * <ul>
 *   <li>{@link Ack} - Order accepted</li>
 *   <li>{@link CancelAck} - Cancel processed</li>
 *   <li>{@link Trade} - Execution report (sent to both buyer and seller)</li>
 *   <li>{@link TopOfBookUpdate} - BBO change (broadcast via multicast)</li>
 * </ul>
 */
public sealed interface OutputMessage 
    permits Ack, CancelAck, Trade, TopOfBookUpdate {
    
    /**
     * Message type discriminator for binary protocol.
     */
    enum Type {
        ACK((byte) 'A'),
        CANCEL_ACK((byte) 'X'),
        TRADE((byte) 'T'),
        TOP_OF_BOOK((byte) 'B');
        
        private final byte wire;
        
        Type(byte wire) {
            this.wire = wire;
        }
        
        public byte wire() {
            return wire;
        }
        
        public static Type fromWire(byte wire) {
            // Bounded: only 4 valid values (Power of Ten Rule 2)
            return switch (wire) {
                case (byte) 'A' -> ACK;
                case (byte) 'X' -> CANCEL_ACK;
                case (byte) 'T' -> TRADE;
                case (byte) 'B' -> TOP_OF_BOOK;
                default -> throw new IllegalArgumentException(
                    "Invalid output message type: " + (char) wire);
            };
        }
    }
    
    /**
     * Get the message type discriminator.
     */
    Type type();
    
    /**
     * Get the symbol for this message.
     * 
     * <p>Every output message includes symbol for stateless routing.
     */
    Symbol symbol();
    
    // =========================================================================
    // Factory Methods (mirrors Rust OutputMessage impl)
    // =========================================================================
    
    /**
     * Create an order acknowledgement.
     */
    static Ack ack(int userId, int userOrderId, Symbol symbol) {
        return new Ack(userId, userOrderId, symbol);
    }
    
    /**
     * Create a cancel acknowledgement.
     */
    static CancelAck cancelAck(int userId, int userOrderId, Symbol symbol) {
        return new CancelAck(userId, userOrderId, symbol);
    }
    
    /**
     * Create a trade execution report.
     */
    static Trade trade(
            Symbol symbol,
            int buyUserId,
            int buyUserOrderId,
            int sellUserId,
            int sellUserOrderId,
            int price,
            int quantity) {
        return new Trade(symbol, buyUserId, buyUserOrderId, 
                         sellUserId, sellUserOrderId, price, quantity);
    }
    
    /**
     * Create an active top-of-book update.
     */
    static TopOfBookUpdate topOfBook(Symbol symbol, Side side, int price, int quantity) {
        return TopOfBookUpdate.active(symbol, side, price, quantity);
    }
    
    /**
     * Create an eliminated top-of-book update (no orders on this side).
     */
    static TopOfBookUpdate topOfBookEliminated(Symbol symbol, Side side) {
        return TopOfBookUpdate.eliminated(symbol, side);
    }
}

/**
 * Order acknowledgement message.
 * 
 * <p>Sent to the originating client when an order is accepted.
 * 
 * <p>Size: 16 bytes.
 */
record Ack(
    int userId,
    int userOrderId,
    Symbol symbol
) implements OutputMessage {
    
    public Ack {
        assert userId >= 0 : "userId must be non-negative";
        assert userOrderId >= 0 : "userOrderId must be non-negative";
        assert symbol != null : "symbol cannot be null";
    }
    
    @Override
    public Type type() {
        return Type.ACK;
    }
    
    @Override
    public String toString() {
        return String.format("Ack[user=%d, orderId=%d, symbol=%s]",
            userId, userOrderId, symbol);
    }
}

/**
 * Cancel acknowledgement message.
 * 
 * <p>Sent to the originating client when a cancel is processed.
 * Always sent regardless of whether the order was found.
 * 
 * <p>Size: 16 bytes.
 */
record CancelAck(
    int userId,
    int userOrderId,
    Symbol symbol
) implements OutputMessage {
    
    public CancelAck {
        assert userId >= 0 : "userId must be non-negative";
        assert userOrderId >= 0 : "userOrderId must be non-negative";
        assert symbol != null : "symbol cannot be null";
    }
    
    @Override
    public Type type() {
        return Type.CANCEL_ACK;
    }
    
    @Override
    public String toString() {
        return String.format("CancelAck[user=%d, orderId=%d, symbol=%s]",
            userId, userOrderId, symbol);
    }
}

/**
 * Trade execution report.
 * 
 * <p>Sent to both buyer and seller when a match occurs.
 * Also broadcast via multicast for market data.
 * 
 * <p>Size: 32 bytes.
 */
record Trade(
    Symbol symbol,
    int buyUserId,
    int buyUserOrderId,
    int sellUserId,
    int sellUserOrderId,
    int price,
    int quantity
) implements OutputMessage {
    
    public Trade {
        assert symbol != null : "symbol cannot be null";
        assert buyUserId >= 0 : "buyUserId must be non-negative";
        assert buyUserOrderId >= 0 : "buyUserOrderId must be non-negative";
        assert sellUserId >= 0 : "sellUserId must be non-negative";
        assert sellUserOrderId >= 0 : "sellUserOrderId must be non-negative";
        assert price > 0 : "trade price must be positive";
        assert quantity > 0 : "trade quantity must be positive";
    }
    
    @Override
    public Type type() {
        return Type.TRADE;
    }
    
    @Override
    public String toString() {
        return String.format("Trade[%s %d@%d, buyer=%d/%d, seller=%d/%d]",
            symbol, quantity, price, buyUserId, buyUserOrderId, 
            sellUserId, sellUserOrderId);
    }
}

/**
 * Top-of-book update message.
 * 
 * <p>Broadcast via multicast when BBO changes.
 * Can indicate either an active level or elimination (no orders).
 * 
 * <p>Size: 20 bytes.
 */
record TopOfBookUpdate(
    Symbol symbol,
    Side side,
    boolean eliminated,
    int price,
    int quantity
) implements OutputMessage {
    
    public TopOfBookUpdate {
        assert symbol != null : "symbol cannot be null";
        assert side != null : "side cannot be null";
        assert price >= 0 : "price must be non-negative";
        assert quantity >= 0 : "quantity must be non-negative";
        // Invariant: eliminated iff price==0 and quantity==0
        assert eliminated == (price == 0 && quantity == 0) 
            : "eliminated flag must match price/quantity";
    }
    
    /**
     * Create an active (non-eliminated) top-of-book update.
     */
    public static TopOfBookUpdate active(Symbol symbol, Side side, int price, int quantity) {
        assert price > 0 : "active TOB must have price > 0";
        assert quantity > 0 : "active TOB must have quantity > 0";
        return new TopOfBookUpdate(symbol, side, false, price, quantity);
    }
    
    /**
     * Create an eliminated top-of-book update (no orders on this side).
     */
    public static TopOfBookUpdate eliminated(Symbol symbol, Side side) {
        return new TopOfBookUpdate(symbol, side, true, 0, 0);
    }
    
    /**
     * Check if this side has been eliminated (no orders).
     */
    public boolean isEliminated() {
        return eliminated;
    }
    
    /**
     * Check if this side is active (has orders).
     */
    public boolean isActive() {
        return !eliminated;
    }
    
    @Override
    public Type type() {
        return Type.TOP_OF_BOOK;
    }
    
    @Override
    public String toString() {
        if (eliminated) {
            return String.format("TopOfBook[%s %s ELIMINATED]", symbol, side);
        }
        return String.format("TopOfBook[%s %s %d@%d]", symbol, side, quantity, price);
    }
}
