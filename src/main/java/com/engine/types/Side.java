package com.engine.types;

/**
 * Order side: Buy or Sell.
 * 
 * <p>Design: Single-byte representation for compact message encoding.
 * Matches Rust enum wire format.
 */
public enum Side {
    BUY((byte) 0),
    SELL((byte) 1);

    private final byte wire;

    Side(byte wire) {
        this.wire = wire;
    }

    /**
     * Wire format value for binary protocol.
     */
    public byte wire() {
        return wire;
    }

    /**
     * Get the opposing side.
     */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }

    /**
     * Parse from wire format.
     * 
     * @param wire byte value (0=BUY, 1=SELL)
     * @return Side enum
     * @throws IllegalArgumentException if wire value is invalid
     */
    public static Side fromWire(byte wire) {
        // Bounded: only 2 valid values
        if (wire == 0) return BUY;
        if (wire == 1) return SELL;
        throw new IllegalArgumentException("Invalid side wire value: " + wire);
    }

    /**
     * Parse from CSV character.
     * 
     * @param c 'B' or 'S'
     * @return Side enum
     * @throws IllegalArgumentException if character is invalid
     */
    public static Side fromChar(char c) {
        if (c == 'B' || c == 'b') return BUY;
        if (c == 'S' || c == 's') return SELL;
        throw new IllegalArgumentException("Invalid side char: " + c);
    }

    /**
     * CSV format character.
     */
    public char toChar() {
        return this == BUY ? 'B' : 'S';
    }
}
