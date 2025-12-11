package com.engine.protocol;

import com.engine.messages.*;
import com.engine.types.Side;
import com.engine.types.Symbol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * CSV protocol codec for human-readable message encoding/decoding.
 * 
 * <h2>Input Format (Client → Server)</h2>
 * <pre>
 * NewOrder:     N, userId, symbol, price, qty, side(B/S), userOrderId
 * Cancel:       C, userId, userOrderId
 * Flush:        F
 * QueryTOB:     Q, symbol
 * </pre>
 * 
 * <h2>Output Format (Server → Client)</h2>
 * <pre>
 * Ack:          A, userId, userOrderId, symbol
 * CancelAck:    X, userId, userOrderId, symbol
 * Trade:        T, symbol, buyUserId, buyOrderId, sellUserId, sellOrderId, price, qty
 * TopOfBook:    B, symbol, side(B/S), price, qty
 * TOB Elim:     B, symbol, side(B/S), -, -
 * </pre>
 * 
 * <h2>Design Notes</h2>
 * <ul>
 *   <li>Lines starting with '#' are comments (ignored)</li>
 *   <li>Blank lines are ignored</li>
 *   <li>Whitespace around commas is trimmed</li>
 *   <li>Case-insensitive for side (B/b, S/s)</li>
 * </ul>
 */
public final class CsvCodec implements Codec {
    
    /** Maximum line length to prevent buffer overflow. */
    private static final int MAX_LINE_LENGTH = 256;
    
    /** Singleton instance (stateless codec). */
    public static final CsvCodec INSTANCE = new CsvCodec();
    
    private CsvCodec() {}
    
    @Override
    public Type type() {
        return Type.CSV;
    }
    
    // =========================================================================
    // Input Decoding
    // =========================================================================
    
    @Override
    public Optional<InputMessage> decodeInput(ByteBuffer buffer) throws ProtocolException {
        // Read until newline
        String line = readLine(buffer);
        if (line == null) {
            return Optional.empty();
        }
        return decodeInputLine(line);
    }
    
    @Override
    public Optional<InputMessage> decodeInputLine(String line) throws ProtocolException {
        String trimmed = line.trim();
        
        // Skip blank lines and comments
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return Optional.empty();
        }
        
        String[] tokens = splitAndTrim(trimmed);
        if (tokens.length == 0) {
            return Optional.empty();
        }
        
        char msgType = tokens[0].isEmpty() ? '\0' : tokens[0].charAt(0);
        
        return switch (msgType) {
            case 'N', 'n' -> Optional.of(parseNewOrder(tokens));
            case 'C', 'c' -> Optional.of(parseCancel(tokens));
            case 'F', 'f' -> parseFlush(tokens);
            case 'Q', 'q' -> Optional.of(parseQueryTob(tokens));
            default -> throw ProtocolException.unknownMessageType(msgType);
        };
    }
    
    private InputMessage parseNewOrder(String[] tokens) throws ProtocolException {
        // N, userId, symbol, price, qty, side, userOrderId
        if (tokens.length != 7) {
            throw ProtocolException.invalidFormat(
                "NewOrder requires 7 fields, got " + tokens.length);
        }
        
        try {
            int userId = Integer.parseInt(tokens[1]);
            Symbol symbol = Symbol.of(tokens[2]);
            int price = Integer.parseInt(tokens[3]);
            int quantity = Integer.parseInt(tokens[4]);
            Side side = parseSide(tokens[5]);
            int userOrderId = Integer.parseInt(tokens[6]);
            
            if (quantity <= 0) {
                throw ProtocolException.invalidField("quantity", "must be positive");
            }
            if (price < 0) {
                throw ProtocolException.invalidField("price", "must be non-negative");
            }
            
            return NewOrder.of(userId, userOrderId, symbol, price, quantity, side);
            
        } catch (NumberFormatException e) {
            throw ProtocolException.invalidFormat("Invalid number: " + e.getMessage());
        }
    }
    
    private InputMessage parseCancel(String[] tokens) throws ProtocolException {
        // C, userId, userOrderId
        if (tokens.length != 3) {
            throw ProtocolException.invalidFormat(
                "Cancel requires 3 fields, got " + tokens.length);
        }
        
        try {
            int userId = Integer.parseInt(tokens[1]);
            int userOrderId = Integer.parseInt(tokens[2]);
            return Cancel.of(userId, userOrderId);
            
        } catch (NumberFormatException e) {
            throw ProtocolException.invalidFormat("Invalid number: " + e.getMessage());
        }
    }
    
    private Optional<InputMessage> parseFlush(String[] tokens) throws ProtocolException {
        // F
        if (tokens.length != 1) {
            throw ProtocolException.invalidFormat(
                "Flush requires 1 field, got " + tokens.length);
        }
        return Optional.of(Flush.INSTANCE);
    }
    
    private InputMessage parseQueryTob(String[] tokens) throws ProtocolException {
        // Q, symbol
        if (tokens.length != 2) {
            throw ProtocolException.invalidFormat(
                "QueryTOB requires 2 fields, got " + tokens.length);
        }
        Symbol symbol = Symbol.of(tokens[1]);
        return new TopOfBookQuery(symbol);
    }
    
    private Side parseSide(String s) throws ProtocolException {
        if (s.isEmpty()) {
            throw ProtocolException.invalidField("side", "empty");
        }
        char c = s.charAt(0);
        return switch (c) {
            case 'B', 'b' -> Side.BUY;
            case 'S', 's' -> Side.SELL;
            default -> throw ProtocolException.invalidField("side", "must be B or S");
        };
    }
    
    // =========================================================================
    // Output Encoding
    // =========================================================================
    
    @Override
    public void encodeOutput(OutputMessage message, ByteBuffer buffer) throws ProtocolException {
        String line = encodeOutputLine(message) + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.US_ASCII);
        
        if (buffer.remaining() < bytes.length) {
            throw ProtocolException.truncated("output buffer");
        }
        
        buffer.put(bytes);
    }
    
    @Override
    public String encodeOutputLine(OutputMessage message) {
        return switch (message) {
            case Ack ack -> String.format("A, %d, %d, %s",
                ack.userId(), ack.userOrderId(), ack.symbol());
                
            case CancelAck cancelAck -> String.format("X, %d, %d, %s",
                cancelAck.userId(), cancelAck.userOrderId(), cancelAck.symbol());
                
            case Trade trade -> String.format("T, %s, %d, %d, %d, %d, %d, %d",
                trade.symbol(),
                trade.buyUserId(), trade.buyUserOrderId(),
                trade.sellUserId(), trade.sellUserOrderId(),
                trade.price(), trade.quantity());
                
            case TopOfBookUpdate tob -> {
                char sideChar = tob.side() == Side.BUY ? 'B' : 'S';
                if (tob.isEliminated()) {
                    yield String.format("B, %s, %c, -, -", tob.symbol(), sideChar);
                } else {
                    yield String.format("B, %s, %c, %d, %d",
                        tob.symbol(), sideChar, tob.price(), tob.quantity());
                }
            }
        };
    }
    
    // =========================================================================
    // Input Encoding (for clients)
    // =========================================================================
    
    @Override
    public void encodeInput(InputMessage message, ByteBuffer buffer) throws ProtocolException {
        String line = encodeInputLine(message) + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.US_ASCII);
        
        if (buffer.remaining() < bytes.length) {
            throw ProtocolException.truncated("input buffer");
        }
        
        buffer.put(bytes);
    }
    
    @Override
    public String encodeInputLine(InputMessage message) {
        return switch (message) {
            case NewOrder order -> String.format("N, %d, %s, %d, %d, %c, %d",
                order.userId(), order.symbol(), order.price(), order.quantity(),
                order.side() == Side.BUY ? 'B' : 'S', order.userOrderId());
                
            case Cancel cancel -> String.format("C, %d, %d",
                cancel.userId(), cancel.userOrderId());
                
            case Flush flush -> "F";
                
            case TopOfBookQuery query -> String.format("Q, %s", query.symbol());
        };
    }
    
    // =========================================================================
    // Output Decoding (for clients)
    // =========================================================================
    
    @Override
    public Optional<OutputMessage> decodeOutput(ByteBuffer buffer) throws ProtocolException {
        String line = readLine(buffer);
        if (line == null) {
            return Optional.empty();
        }
        return decodeOutputLine(line);
    }
    
    @Override
    public Optional<OutputMessage> decodeOutputLine(String line) throws ProtocolException {
        String trimmed = line.trim();
        
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return Optional.empty();
        }
        
        String[] tokens = splitAndTrim(trimmed);
        if (tokens.length == 0) {
            return Optional.empty();
        }
        
        char msgType = tokens[0].isEmpty() ? '\0' : tokens[0].charAt(0);
        
        return switch (msgType) {
            case 'A', 'a' -> Optional.of(parseAck(tokens));
            case 'X', 'x' -> Optional.of(parseCancelAck(tokens));
            case 'T', 't' -> Optional.of(parseTrade(tokens));
            case 'B', 'b' -> Optional.of(parseTopOfBook(tokens));
            default -> throw ProtocolException.unknownMessageType(msgType);
        };
    }
    
    private OutputMessage parseAck(String[] tokens) throws ProtocolException {
        // A, userId, userOrderId, symbol
        if (tokens.length != 4) {
            throw ProtocolException.invalidFormat(
                "Ack requires 4 fields, got " + tokens.length);
        }
        
        try {
            int userId = Integer.parseInt(tokens[1]);
            int userOrderId = Integer.parseInt(tokens[2]);
            Symbol symbol = Symbol.of(tokens[3]);
            return new Ack(userId, userOrderId, symbol);
            
        } catch (NumberFormatException e) {
            throw ProtocolException.invalidFormat("Invalid number: " + e.getMessage());
        }
    }
    
    private OutputMessage parseCancelAck(String[] tokens) throws ProtocolException {
        // X, userId, userOrderId, symbol
        if (tokens.length != 4) {
            throw ProtocolException.invalidFormat(
                "CancelAck requires 4 fields, got " + tokens.length);
        }
        
        try {
            int userId = Integer.parseInt(tokens[1]);
            int userOrderId = Integer.parseInt(tokens[2]);
            Symbol symbol = Symbol.of(tokens[3]);
            return new CancelAck(userId, userOrderId, symbol);
            
        } catch (NumberFormatException e) {
            throw ProtocolException.invalidFormat("Invalid number: " + e.getMessage());
        }
    }
    
    private OutputMessage parseTrade(String[] tokens) throws ProtocolException {
        // T, symbol, buyUserId, buyOrderId, sellUserId, sellOrderId, price, qty
        if (tokens.length != 8) {
            throw ProtocolException.invalidFormat(
                "Trade requires 8 fields, got " + tokens.length);
        }
        
        try {
            Symbol symbol = Symbol.of(tokens[1]);
            int buyUserId = Integer.parseInt(tokens[2]);
            int buyOrderId = Integer.parseInt(tokens[3]);
            int sellUserId = Integer.parseInt(tokens[4]);
            int sellOrderId = Integer.parseInt(tokens[5]);
            int price = Integer.parseInt(tokens[6]);
            int quantity = Integer.parseInt(tokens[7]);
            
            return new Trade(symbol, buyUserId, buyOrderId, sellUserId, sellOrderId, price, quantity);
            
        } catch (NumberFormatException e) {
            throw ProtocolException.invalidFormat("Invalid number: " + e.getMessage());
        }
    }
    
    private OutputMessage parseTopOfBook(String[] tokens) throws ProtocolException {
        // B, symbol, side, price, qty  OR  B, symbol, side, -, -
        if (tokens.length != 5) {
            throw ProtocolException.invalidFormat(
                "TopOfBook requires 5 fields, got " + tokens.length);
        }
        
        Symbol symbol = Symbol.of(tokens[1]);
        Side side = parseSide(tokens[2]);
        
        // Check for eliminated
        if (tokens[3].equals("-") && tokens[4].equals("-")) {
            return TopOfBookUpdate.eliminated(symbol, side);
        }
        
        try {
            int price = Integer.parseInt(tokens[3]);
            int quantity = Integer.parseInt(tokens[4]);
            return TopOfBookUpdate.active(symbol, side, price, quantity);
            
        } catch (NumberFormatException e) {
            throw ProtocolException.invalidFormat("Invalid number: " + e.getMessage());
        }
    }
    
    // =========================================================================
    // Helpers
    // =========================================================================
    
    /**
     * Read a line from the buffer, returning null if incomplete.
     */
    private String readLine(ByteBuffer buffer) {
        int start = buffer.position();
        int limit = buffer.limit();
        
        // Find newline
        int newlinePos = -1;
        for (int i = start; i < limit && i < start + MAX_LINE_LENGTH; i++) {
            byte b = buffer.get(i);
            if (b == '\n') {
                newlinePos = i;
                break;
            }
        }
        
        if (newlinePos == -1) {
            // No complete line yet
            return null;
        }
        
        // Extract line (excluding newline)
        int lineLength = newlinePos - start;
        byte[] lineBytes = new byte[lineLength];
        buffer.get(lineBytes);
        buffer.get(); // consume newline
        
        String line = new String(lineBytes, StandardCharsets.US_ASCII);
        
        // Strip \r if present (Windows line endings)
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        
        return line;
    }
    
    /**
     * Split string by comma and trim each token.
     */
    private String[] splitAndTrim(String s) {
        String[] parts = s.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }
}
