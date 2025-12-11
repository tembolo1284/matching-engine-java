package com.engine.types;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class Symbol {
    
    public static final int SIZE = 8;
    public static final Symbol UNKNOWN = new Symbol(0L, "<UNK>");
    
    private final long packed;
    private final String display;
    
    private Symbol(long packed, String display) {
        this.packed = packed;
        this.display = display;
    }
    
    public static Symbol of(String s) {
        if (s == null || s.isEmpty()) {
            return UNKNOWN;
        }
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        int len = Math.min(bytes.length, SIZE);
        long packed = 0;
        for (int i = 0; i < len; i++) {
            packed |= ((long) (bytes[i] & 0xFF)) << (56 - i * 8);
        }
        return new Symbol(packed, s.length() <= SIZE ? s : s.substring(0, SIZE));
    }
    
    public static Symbol fromPacked(long packed) {
        if (packed == 0) return UNKNOWN;
        byte[] bytes = new byte[SIZE];
        int len = 0;
        for (int i = 0; i < SIZE; i++) {
            byte b = (byte) ((packed >> (56 - i * 8)) & 0xFF);
            if (b == 0) break;
            bytes[i] = b;
            len++;
        }
        return new Symbol(packed, new String(bytes, 0, len, StandardCharsets.US_ASCII));
    }
    
    public static Symbol fromBuffer(ByteBuffer buf) {
        byte[] bytes = new byte[SIZE];
        buf.get(bytes);
        long packed = 0;
        int len = 0;
        for (int i = 0; i < SIZE; i++) {
            packed |= ((long) (bytes[i] & 0xFF)) << (56 - i * 8);
            if (bytes[i] != 0 && len == i) len = i + 1;
        }
        if (packed == 0) return UNKNOWN;
        return new Symbol(packed, new String(bytes, 0, len, StandardCharsets.US_ASCII));
    }
    
    public long packed() {
        return packed;
    }
    
    public void toBuffer(ByteBuffer buf) {
        for (int i = 0; i < SIZE; i++) {
            buf.put((byte) ((packed >> (56 - i * 8)) & 0xFF));
        }
    }
    
    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Symbol s && packed == s.packed);
    }
    
    @Override
    public int hashCode() {
        return Long.hashCode(packed);
    }
    
    @Override
    public String toString() {
        return display;
    }
}
