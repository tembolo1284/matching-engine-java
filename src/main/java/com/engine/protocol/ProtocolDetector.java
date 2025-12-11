package com.engine.protocol;

import java.nio.ByteBuffer;

public final class ProtocolDetector {
    
    public enum Protocol { CSV, BINARY, UNKNOWN }
    
    private ProtocolDetector() {}
    
    public static Protocol detect(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) return Protocol.UNKNOWN;
        byte first = buffer.get(buffer.position());
        if (first == WireConstants.MAGIC) return Protocol.BINARY;
        if (Character.isLetter(first) || first == '#') return Protocol.CSV;
        return Protocol.UNKNOWN;
    }
}
