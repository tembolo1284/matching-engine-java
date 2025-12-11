package com.engine.protocol;

import com.engine.messages.InputMessage;
import com.engine.messages.OutputMessage;

import java.nio.ByteBuffer;
import java.util.Optional;

public interface Codec {
    
    enum Type { CSV, BINARY }
    
    Type type();
    Optional<InputMessage> decodeInput(ByteBuffer buffer) throws ProtocolException;
    void encodeOutput(OutputMessage message, ByteBuffer buffer);
}
