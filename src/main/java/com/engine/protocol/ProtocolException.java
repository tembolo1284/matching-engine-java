package com.engine.protocol;

/**
 * Exception thrown when protocol encoding/decoding fails.
 * 
 * <p>Error types:
 * <ul>
 *   <li>{@link ErrorType#TRUNCATED} - Buffer too short for expected message</li>
 *   <li>{@link ErrorType#INVALID_MAGIC} - Wrong magic byte (binary protocol)</li>
 *   <li>{@link ErrorType#UNKNOWN_MESSAGE_TYPE} - Unrecognized message type</li>
 *   <li>{@link ErrorType#INVALID_FIELD} - Field value out of range or malformed</li>
 *   <li>{@link ErrorType#INVALID_FORMAT} - General format error (CSV parsing, etc.)</li>
 * </ul>
 */
public class ProtocolException extends Exception {
    
    public enum ErrorType {
        TRUNCATED,
        INVALID_MAGIC,
        UNKNOWN_MESSAGE_TYPE,
        INVALID_FIELD,
        INVALID_FORMAT
    }
    
    private final ErrorType errorType;
    private final String details;
    
    public ProtocolException(ErrorType errorType, String details) {
        super(formatMessage(errorType, details));
        this.errorType = errorType;
        this.details = details;
    }
    
    public ProtocolException(ErrorType errorType, String details, Throwable cause) {
        super(formatMessage(errorType, details), cause);
        this.errorType = errorType;
        this.details = details;
    }
    
    public ErrorType errorType() {
        return errorType;
    }
    
    public String details() {
        return details;
    }
    
    private static String formatMessage(ErrorType type, String details) {
        return String.format("%s: %s", type, details);
    }
    
    // === Factory methods for common errors ===
    
    public static ProtocolException truncated(String context) {
        return new ProtocolException(ErrorType.TRUNCATED, 
            "Buffer too short for " + context);
    }
    
    public static ProtocolException invalidMagic(byte got, byte expected) {
        return new ProtocolException(ErrorType.INVALID_MAGIC,
            String.format("got 0x%02X, expected 0x%02X", got, expected));
    }
    
    public static ProtocolException unknownMessageType(byte type) {
        return new ProtocolException(ErrorType.UNKNOWN_MESSAGE_TYPE,
            String.format("0x%02X ('%c')", type, (char) type));
    }
    
    public static ProtocolException unknownMessageType(char type) {
        return new ProtocolException(ErrorType.UNKNOWN_MESSAGE_TYPE,
            String.format("'%c'", type));
    }
    
    public static ProtocolException invalidField(String field, String reason) {
        return new ProtocolException(ErrorType.INVALID_FIELD,
            field + ": " + reason);
    }
    
    public static ProtocolException invalidFormat(String reason) {
        return new ProtocolException(ErrorType.INVALID_FORMAT, reason);
    }
}
