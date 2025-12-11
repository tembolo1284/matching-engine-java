package com.engine.protocol;

/**
 * Wire protocol constants matching Rust/Zig binary format.
 * 
 * <p>All multi-byte integers are big-endian (network order).
 */
public final class WireConstants {
    
    private WireConstants() {} // No instantiation
    
    // =========================================================================
    // Magic & Sizes
    // =========================================================================
    
    /** Magic byte for binary protocol detection. ASCII 'M' for "Matching engine". */
    public static final byte MAGIC_BYTE = 0x4D; // 'M'
    
    /** Fixed symbol size on wire (null-padded). */
    public static final int SYMBOL_SIZE = 8;
    
    // =========================================================================
    // Input Message Sizes (Client → Server)
    // =========================================================================
    
    /** NewOrder message size: magic(1) + type(1) + userId(4) + symbol(8) + price(4) + qty(4) + side(1) + orderId(4) */
    public static final int NEW_ORDER_SIZE = 27;
    
    /** Cancel message size: magic(1) + type(1) + userId(4) + symbol(8) + orderId(4) */
    public static final int CANCEL_SIZE = 18;
    
    /** Flush message size: magic(1) + type(1) */
    public static final int FLUSH_SIZE = 2;
    
    // =========================================================================
    // Output Message Sizes (Server → Client)
    // =========================================================================
    
    /** Ack message size: magic(1) + type(1) + symbol(8) + userId(4) + orderId(4) */
    public static final int ACK_SIZE = 18;
    
    /** CancelAck message size: magic(1) + type(1) + symbol(8) + userId(4) + orderId(4) */
    public static final int CANCEL_ACK_SIZE = 18;
    
    /** Trade message size: magic(1) + type(1) + symbol(8) + buyUser(4) + buyOrder(4) + sellUser(4) + sellOrder(4) + price(4) + qty(4) */
    public static final int TRADE_SIZE = 34;
    
    /** TopOfBook message size: magic(1) + type(1) + symbol(8) + side(1) + price(4) + qty(4) + padding(1) */
    public static final int TOP_OF_BOOK_SIZE = 20;
    
    // =========================================================================
    // Input Message Types (Client → Server)
    // =========================================================================
    
    public static final byte INPUT_NEW_ORDER = (byte) 'N';  // 0x4E
    public static final byte INPUT_CANCEL = (byte) 'C';     // 0x43
    public static final byte INPUT_FLUSH = (byte) 'F';      // 0x46
    
    // =========================================================================
    // Output Message Types (Server → Client)
    // =========================================================================
    
    public static final byte OUTPUT_ACK = (byte) 'A';           // 0x41
    public static final byte OUTPUT_CANCEL_ACK = (byte) 'X';    // 0x58
    public static final byte OUTPUT_TRADE = (byte) 'T';         // 0x54
    public static final byte OUTPUT_TOP_OF_BOOK = (byte) 'B';   // 0x42
    
    // =========================================================================
    // Side Encoding
    // =========================================================================
    
    public static final byte SIDE_BUY = (byte) 'B';
    public static final byte SIDE_SELL = (byte) 'S';
    
    // =========================================================================
    // Validation
    // =========================================================================
    
    /**
     * Check if byte is valid magic.
     */
    public static boolean isValidMagic(byte b) {
        return b == MAGIC_BYTE;
    }
    
    /**
     * Check if byte is valid input message type.
     */
    public static boolean isValidInputType(byte b) {
        return b == INPUT_NEW_ORDER || b == INPUT_CANCEL || b == INPUT_FLUSH;
    }
    
    /**
     * Check if byte is valid output message type.
     */
    public static boolean isValidOutputType(byte b) {
        return b == OUTPUT_ACK || b == OUTPUT_CANCEL_ACK 
            || b == OUTPUT_TRADE || b == OUTPUT_TOP_OF_BOOK;
    }
    
    /**
     * Get expected input message size for a given type.
     * 
     * @return message size, or -1 if unknown type
     */
    public static int inputMessageSize(byte type) {
        return switch (type) {
            case INPUT_NEW_ORDER -> NEW_ORDER_SIZE;
            case INPUT_CANCEL -> CANCEL_SIZE;
            case INPUT_FLUSH -> FLUSH_SIZE;
            default -> -1;
        };
    }
    
    /**
     * Get expected output message size for a given type.
     * 
     * @return message size, or -1 if unknown type
     */
    public static int outputMessageSize(byte type) {
        return switch (type) {
            case OUTPUT_ACK -> ACK_SIZE;
            case OUTPUT_CANCEL_ACK -> CANCEL_ACK_SIZE;
            case OUTPUT_TRADE -> TRADE_SIZE;
            case OUTPUT_TOP_OF_BOOK -> TOP_OF_BOOK_SIZE;
            default -> -1;
        };
    }
}
