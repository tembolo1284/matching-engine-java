package com.engine.types;

public enum Side {
    BUY((byte) 'B'),
    SELL((byte) 'S');
    
    private final byte wire;
    
    Side(byte wire) {
        this.wire = wire;
    }
    
    public byte wire() {
        return wire;
    }
    
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
    
    public static Side fromWire(byte b) {
        return switch (b) {
            case 'B', 'b' -> BUY;
            case 'S', 's' -> SELL;
            default -> throw new IllegalArgumentException("Invalid side: " + (char) b);
        };
    }
}
