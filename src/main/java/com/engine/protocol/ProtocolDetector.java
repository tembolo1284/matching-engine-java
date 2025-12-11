package com.engine.protocol;

import java.nio.ByteBuffer;

import static com.engine.protocol.WireConstants.*;

/**
 * Auto-detect protocol from first bytes of a message.
 * 
 * <p>Detection rules:
 * <ul>
 *   <li>Binary: First byte is magic (0x4D 'M')</li>
 *   <li>CSV: First byte is ASCII letter (N, C, F, Q) or whitespace/comment</li>
 *   <li>FIX: Starts with "8=FIX"</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * ByteBuffer buffer = ...;
 * Codec codec = ProtocolDetector.detect(buffer);
 * Optional<InputMessage> msg = codec.decodeInput(buffer);
 * }</pre>
 */
public final class ProtocolDetector {
    
    private ProtocolDetector() {}
    
    /**
     * Detected protocol type.
     */
    public enum Protocol {
        BINARY,
        CSV,
        FIX,
        UNKNOWN
    }
    
    /**
     * Detect protocol from buffer contents.
     * 
     * <p>Does not consume any bytes from the buffer.
     * 
     * @param buffer the input buffer (position unchanged)
     * @return detected protocol type
     */
    public static Protocol detect(ByteBuffer buffer) {
        if (buffer.remaining() < 1) {
            return Protocol.UNKNOWN;
        }
        
        byte first = buffer.get(buffer.position());
        
        // Binary protocol: magic byte
        if (first == MAGIC_BYTE) {
            return Protocol.BINARY;
        }
        
        // FIX protocol: starts with "8=FIX"
        if (first == '8' && buffer.remaining() >= 5) {
            if (peekString(buffer, 5).equals("8=FIX")) {
                return Protocol.FIX;
            }
        }
        
        // CSV protocol: ASCII text (letters, digits, whitespace, #)
        if (isAsciiText(first)) {
            return Protocol.CSV;
        }
        
        return Protocol.UNKNOWN;
    }
    
    /**
     * Get the appropriate codec for the detected protocol.
     * 
     * @param buffer the input buffer
     * @return codec instance, or null if unknown protocol
     */
    public static Codec detectCodec(ByteBuffer buffer) {
        Protocol protocol = detect(buffer);
        return switch (protocol) {
            case BINARY -> BinaryCodec.INSTANCE;
            case CSV -> CsvCodec.INSTANCE;
            case FIX -> null; // FIX codec not implemented yet
            case UNKNOWN -> null;
        };
    }
    
    /**
     * Check if byte is valid start of CSV message.
     */
    private static boolean isAsciiText(byte b) {
        // Valid CSV starts: N, C, F, Q (message types)
        // Also allow: whitespace, # (comments), digits
        return (b >= 'A' && b <= 'Z') 
            || (b >= 'a' && b <= 'z')
            || (b >= '0' && b <= '9')
            || b == ' ' || b == '\t' || b == '\r' || b == '\n'
            || b == '#';
    }
    
    /**
     * Peek at string without consuming buffer.
     */
    private static String peekString(ByteBuffer buffer, int length) {
        int pos = buffer.position();
        byte[] bytes = new byte[Math.min(length, buffer.remaining())];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = buffer.get(pos + i);
        }
        return new String(bytes);
    }
    
    /**
     * Check if buffer contains a complete message.
     * 
     * <p>For binary protocol, checks if enough bytes are present.
     * For CSV, checks for newline terminator.
     * 
     * @param buffer the input buffer
     * @return true if a complete message is available
     */
    public static boolean hasCompleteMessage(ByteBuffer buffer) {
        Protocol protocol = detect(buffer);
        
        return switch (protocol) {
            case BINARY -> hasCompleteBinaryMessage(buffer);
            case CSV -> hasCompleteCsvMessage(buffer);
            case FIX -> hasCompleteFixMessage(buffer);
            case UNKNOWN -> false;
        };
    }
    
    private static boolean hasCompleteBinaryMessage(ByteBuffer buffer) {
        if (buffer.remaining() < 2) {
            return false;
        }
        
        byte msgType = buffer.get(buffer.position() + 1);
        int expectedSize = inputMessageSize(msgType);
        
        if (expectedSize < 0) {
            // Try output message size
            expectedSize = outputMessageSize(msgType);
        }
        
        return expectedSize > 0 && buffer.remaining() >= expectedSize;
    }
    
    private static boolean hasCompleteCsvMessage(ByteBuffer buffer) {
        // Look for newline
        for (int i = buffer.position(); i < buffer.limit(); i++) {
            if (buffer.get(i) == '\n') {
                return true;
            }
        }
        return false;
    }
    
    private static boolean hasCompleteFixMessage(ByteBuffer buffer) {
        // FIX messages end with SOH (0x01) + "10=xxx" + SOH
        // Simplified: look for checksum field
        // For now, just check for basic structure
        return false; // Not implemented
    }
}
