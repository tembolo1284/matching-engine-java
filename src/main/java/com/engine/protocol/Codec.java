package com.engine.protocol;

import com.engine.messages.InputMessage;
import com.engine.messages.OutputMessage;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

/**
 * Protocol codec interface for encoding/decoding engine messages.
 * 
 * <p>Implementations:
 * <ul>
 *   <li>{@link CsvCodec} - Human-readable, netcat-friendly</li>
 *   <li>{@link BinaryCodec} - Low-latency production protocol</li>
 * </ul>
 * 
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Stateless: Codecs don't maintain connection state</li>
 *   <li>Zero-allocation decoding: Write to pre-allocated buffers where possible</li>
 *   <li>Symmetric: Can encode what it decodes and vice versa</li>
 * </ul>
 */
public interface Codec {
    
    /**
     * Codec type identifier.
     */
    enum Type {
        CSV,
        BINARY,
        FIX
    }
    
    /**
     * Get the codec type.
     */
    Type type();
    
    // =========================================================================
    // Input Message Decoding (Client → Server)
    // =========================================================================
    
    /**
     * Decode a single input message from bytes.
     * 
     * @param buffer the input buffer
     * @return decoded message, or empty if incomplete/invalid
     * @throws ProtocolException if the message is malformed
     */
    Optional<InputMessage> decodeInput(ByteBuffer buffer) throws ProtocolException;
    
    /**
     * Decode a single input message from a string line (for text protocols).
     * 
     * @param line the input line
     * @return decoded message, or empty if blank/comment
     * @throws ProtocolException if the message is malformed
     */
    default Optional<InputMessage> decodeInputLine(String line) throws ProtocolException {
        throw new UnsupportedOperationException("Line decoding not supported for this codec");
    }
    
    // =========================================================================
    // Output Message Encoding (Server → Client)
    // =========================================================================
    
    /**
     * Encode a single output message to the buffer.
     * 
     * @param message the message to encode
     * @param buffer the output buffer (must have sufficient remaining capacity)
     * @throws ProtocolException if encoding fails
     */
    void encodeOutput(OutputMessage message, ByteBuffer buffer) throws ProtocolException;
    
    /**
     * Encode a single output message to a string line (for text protocols).
     * 
     * @param message the message to encode
     * @return formatted string
     */
    default String encodeOutputLine(OutputMessage message) {
        throw new UnsupportedOperationException("Line encoding not supported for this codec");
    }
    
    /**
     * Encode multiple output messages to the buffer.
     * 
     * @param messages the messages to encode
     * @param buffer the output buffer
     * @throws ProtocolException if encoding fails
     */
    default void encodeOutputs(List<OutputMessage> messages, ByteBuffer buffer) 
            throws ProtocolException {
        for (OutputMessage msg : messages) {
            encodeOutput(msg, buffer);
        }
    }
    
    // =========================================================================
    // Input Message Encoding (for clients / testing)
    // =========================================================================
    
    /**
     * Encode a single input message to the buffer.
     * 
     * <p>Used by clients to send orders to the server.
     * 
     * @param message the message to encode
     * @param buffer the output buffer
     * @throws ProtocolException if encoding fails
     */
    void encodeInput(InputMessage message, ByteBuffer buffer) throws ProtocolException;
    
    /**
     * Encode a single input message to a string line (for text protocols).
     * 
     * @param message the message to encode
     * @return formatted string
     */
    default String encodeInputLine(InputMessage message) {
        throw new UnsupportedOperationException("Line encoding not supported for this codec");
    }
    
    // =========================================================================
    // Output Message Decoding (for clients / testing)
    // =========================================================================
    
    /**
     * Decode a single output message from bytes.
     * 
     * <p>Used by clients to receive responses from the server.
     * 
     * @param buffer the input buffer
     * @return decoded message, or empty if incomplete
     * @throws ProtocolException if the message is malformed
     */
    Optional<OutputMessage> decodeOutput(ByteBuffer buffer) throws ProtocolException;
    
    /**
     * Decode a single output message from a string line (for text protocols).
     * 
     * @param line the input line
     * @return decoded message, or empty if blank/comment
     * @throws ProtocolException if the message is malformed
     */
    default Optional<OutputMessage> decodeOutputLine(String line) throws ProtocolException {
        throw new UnsupportedOperationException("Line decoding not supported for this codec");
    }
}
