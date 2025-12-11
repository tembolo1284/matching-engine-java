package com.engine.protocol;

public final class ProtocolException extends Exception {
    
    public ProtocolException(String message) {
        super(message);
    }
    
    public static ProtocolException truncated(String context) {
        return new ProtocolException("Truncated: " + context);
    }
    
    public static ProtocolException invalidMagic(byte actual) {
        return new ProtocolException("Invalid magic: " + Integer.toHexString(actual & 0xFF));
    }
    
    public static ProtocolException unknownType(byte type) {
        return new ProtocolException("Unknown type: " + (char) type);
    }
    
    public static ProtocolException invalidFormat(String reason) {
        return new ProtocolException("Invalid format: " + reason);
    }
}
