package com.engine.protocol;

public final class WireConstants {
    private WireConstants() {}
    
    public static final byte MAGIC = 0x4D; // 'M'
    public static final int SYMBOL_SIZE = 8;
    
    // Input types
    public static final byte TYPE_NEW_ORDER = 'N';
    public static final byte TYPE_CANCEL = 'C';
    public static final byte TYPE_FLUSH = 'F';
    public static final byte TYPE_QUERY = 'Q';
    
    // Output types
    public static final byte TYPE_ACK = 'A';
    public static final byte TYPE_CANCEL_ACK = 'X';
    public static final byte TYPE_TRADE = 'T';
    public static final byte TYPE_TOB = 'B';
}
