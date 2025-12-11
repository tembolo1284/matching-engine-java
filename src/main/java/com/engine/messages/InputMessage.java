package com.engine.messages;

import com.engine.types.Symbol;

/**
 * Input message to the matching engine.
 * 
 * <p>Design Principles:
 * <ul>
 *   <li>Sealed interface: exhaustive pattern matching in switch</li>
 *   <li>All implementations are immutable value types</li>
 *   <li>No allocation on hot path when using pre-allocated instances</li>
 * </ul>
 * 
 * <p>Message Types:
 * <ul>
 *   <li>{@link NewOrder} - Submit a new order</li>
 *   <li>{@link Cancel} - Cancel an existing order</li>
 *   <li>{@link Flush} - Clear all order books</li>
 *   <li>{@link TopOfBookQuery} - Query current BBO for a symbol</li>
 * </ul>
 */
public sealed interface InputMessage 
    permits NewOrder, Cancel, Flush, TopOfBookQuery {
    
    /**
     * Message type discriminator for binary protocol.
     */
    enum Type {
        NEW_ORDER((byte) 'N'),
        CANCEL((byte) 'C'),
        FLUSH((byte) 'F'),
        QUERY_TOP_OF_BOOK((byte) 'Q');
        
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
                case (byte) 'N' -> NEW_ORDER;
                case (byte) 'C' -> CANCEL;
                case (byte) 'F' -> FLUSH;
                case (byte) 'Q' -> QUERY_TOP_OF_BOOK;
                default -> throw new IllegalArgumentException(
                    "Invalid input message type: " + (char) wire);
            };
        }
    }
    
    /**
     * Get the message type discriminator.
     */
    Type type();
}

/**
 * Flush all order books.
 * 
 * <p>Singleton instance - no allocation needed.
 */
record Flush() implements InputMessage {
    
    /** Singleton instance. */
    public static final Flush INSTANCE = new Flush();
    
    @Override
    public Type type() {
        return Type.FLUSH;
    }
}

/**
 * Query top-of-book for a symbol.
 * 
 * <p>Size: 8 bytes (symbol only).
 */
record TopOfBookQuery(Symbol symbol) implements InputMessage {
    
    public TopOfBookQuery {
        assert symbol != null : "Symbol cannot be null";
    }
    
    @Override
    public Type type() {
        return Type.QUERY_TOP_OF_BOOK;
    }
}
