package com.engine.types;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Fixed 8-byte symbol identifier.
 * 
 * <p>Design Principles (Power of Ten Rule 3 - no allocation after init):
 * <ul>
 *   <li>Fixed size: always 8 bytes, no heap allocation for symbol storage</li>
 *   <li>Padded with zeros: "IBM" becomes ['I','B','M',0,0,0,0,0]</li>
 *   <li>Backed by long: single primitive for fast comparison and hashing</li>
 *   <li>Immutable: safe to share across threads</li>
 * </ul>
 * 
 * <p>Size: 8 bytes (one long field).
 */
public final class Symbol {
    
    /** Maximum symbol length in characters. */
    public static final int MAX_LENGTH = 8;
    
    /** Empty symbol constant. */
    public static final Symbol EMPTY = new Symbol(0L);
    
    /** Unknown symbol (used for cancel acks when order not found). */
    public static final Symbol UNKNOWN = Symbol.of("<UNK>");
    
    /**
     * Packed representation: 8 ASCII bytes in a long.
     * First character in most significant byte (big-endian for string ordering).
     */
    private final long packed;
    
    private Symbol(long packed) {
        this.packed = packed;
    }
    
    /**
     * Create a Symbol from a string.
     * 
     * <p>String is truncated to 8 characters if longer.
     * Non-ASCII characters are replaced with '?'.
     * 
     * @param s symbol string (e.g., "IBM", "AAPL")
     * @return Symbol instance
     * @throws IllegalArgumentException if s is null
     */
    public static Symbol of(String s) {
        if (s == null) {
            throw new IllegalArgumentException("Symbol string cannot be null");
        }
        
        long packed = 0L;
        int len = Math.min(s.length(), MAX_LENGTH);
        
        // Pack characters into long, big-endian (first char in MSB)
        // Bounded loop: max 8 iterations (Power of Ten Rule 2)
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            byte b = (c >= 0 && c < 128) ? (byte) c : (byte) '?';
            packed |= ((long) (b & 0xFF)) << (56 - i * 8);
        }
        
        return new Symbol(packed);
    }
    
    /**
     * Create a Symbol from raw bytes.
     * 
     * @param bytes exactly 8 bytes
     * @return Symbol instance
     * @throws IllegalArgumentException if bytes is null or wrong length
     */
    public static Symbol fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != MAX_LENGTH) {
            throw new IllegalArgumentException("Symbol bytes must be exactly 8 bytes");
        }
        
        long packed = 0L;
        for (int i = 0; i < MAX_LENGTH; i++) {
            packed |= ((long) (bytes[i] & 0xFF)) << (56 - i * 8);
        }
        
        return new Symbol(packed);
    }
    
    /**
     * Create a Symbol from packed long representation.
     * 
     * @param packed the packed long value
     * @return Symbol instance
     */
    public static Symbol fromPacked(long packed) {
        return new Symbol(packed);
    }
    
    /**
     * Get the packed long representation.
     * 
     * <p>Useful for serialization and as a hash key.
     */
    public long packed() {
        return packed;
    }
    
    /**
     * Write symbol bytes into the provided array at offset.
     * 
     * @param dest destination array
     * @param offset starting offset
     * @throws IllegalArgumentException if dest is null or too small
     */
    public void toBytes(byte[] dest, int offset) {
        assert dest != null : "Destination array cannot be null";
        assert offset >= 0 && offset + MAX_LENGTH <= dest.length : "Invalid offset";
        
        for (int i = 0; i < MAX_LENGTH; i++) {
            dest[offset + i] = (byte) (packed >>> (56 - i * 8));
        }
    }
    
    /**
     * Get symbol as new byte array.
     * 
     * <p>Note: Allocates. Use {@link #toBytes(byte[], int)} on hot path.
     */
    public byte[] toBytes() {
        byte[] bytes = new byte[MAX_LENGTH];
        toBytes(bytes, 0);
        return bytes;
    }
    
    /**
     * Check if this is an empty symbol.
     */
    public boolean isEmpty() {
        return packed == 0L;
    }
    
    /**
     * Get the effective length (excluding trailing zeros).
     */
    public int length() {
        int len = 0;
        for (int i = 0; i < MAX_LENGTH; i++) {
            byte b = (byte) (packed >>> (56 - i * 8));
            if (b == 0) break;
            len++;
        }
        return len;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Symbol)) return false;
        return packed == ((Symbol) o).packed;
    }
    
    @Override
    public int hashCode() {
        // Good hash distribution from long
        return Long.hashCode(packed);
    }
    
    @Override
    public String toString() {
        // Note: Allocates. For logging/debugging only, not hot path.
        byte[] bytes = new byte[MAX_LENGTH];
        int len = 0;
        
        for (int i = 0; i < MAX_LENGTH; i++) {
            byte b = (byte) (packed >>> (56 - i * 8));
            if (b == 0) break;
            bytes[len++] = b;
        }
        
        return new String(bytes, 0, len, StandardCharsets.US_ASCII);
    }
}
