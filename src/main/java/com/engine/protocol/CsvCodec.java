package com.engine.protocol;

import com.engine.messages.*;
import com.engine.types.Side;
import com.engine.types.Symbol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * CSV codec for human-readable protocol.
 * 
 * <p>Input format:
 * <pre>
 * N, userId, symbol, price, qty, side(B/S), userOrderId
 * C, userId, userOrderId
 * F
 * Q, symbol
 * </pre>
 * 
 * <p>Output format:
 * <pre>
 * A, userId, userOrderId, symbol
 * X, userId, userOrderId, symbol
 * T, symbol, buyUserId, buyOrderId, sellUserId, sellOrderId, price, qty
 * B, symbol, side(B/S), price, qty
 * B, symbol, side(B/S), -, -  (eliminated)
 * </pre>
 */
public final class CsvCodec implements Codec {
    
    public static final CsvCodec INSTANCE = new CsvCodec();
    
    private CsvCodec() {}
    
    @Override
    public Type type() {
        return Type.CSV;
    }
    
    @Override
    public Optional<InputMessage> decodeInput(ByteBuffer buffer) throws ProtocolException {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        String line = new String(bytes, StandardCharsets.US_ASCII).trim();
        return decodeInputLine(line);
    }
    
    /**
     * Decode a single CSV line.
     */
    public Optional<InputMessage> decodeInputLine(String line) throws ProtocolException {
        if (line.isEmpty() || line.startsWith("#")) {
            return Optional.empty();
        }
        
        String[] parts = line.split(",");
        if (parts.length == 0) {
            return Optional.empty();
        }
        
        String type = parts[0].trim().toUpperCase();
        
        if (type.equals("N")) {
            return Optional.of(decodeNewOrder(parts));
        } else if (type.equals("C")) {
            return Optional.of(decodeCancel(parts));
        } else if (type.equals("F")) {
            return Optional.of(InputMessage.FLUSH);
        } else if (type.equals("Q")) {
            return Optional.of(decodeQuery(parts));
        } else {
            throw ProtocolException.unknownMessageType((byte) type.charAt(0));
        }
    }
    
    private NewOrder decodeNewOrder(String[] parts) throws ProtocolException {
        // N, userId, symbol, price, qty, side, userOrderId
        if (parts.length < 7) {
            throw ProtocolException.invalidFormat(
                "NewOrder requires 7 fields: N, userId, symbol, price, qty, side, orderId");
        }
        
        try {
            int userId = Integer.parseInt(parts[1].trim());
            Symbol symbol = Symbol.of(parts[2].trim());
            int price = Integer.parseInt(parts[3].trim());
            int qty = Integer.parseInt(parts[4].trim());
            Side side = Side.fromChar(parts[5].trim().charAt(0));
            int userOrderId = Integer.parseInt(parts[6].trim());
            
            return new NewOrder(userId, userOrderId, symbol, price, qty, side);
            
        } catch (NumberFormatException e) {
            throw ProtocolException.invalidField("number", e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ProtocolException.invalidField("side", e.getMessage());
        }
    }
    
    private Cancel decodeCancel(String[] parts) throws ProtocolException {
        // C, userId, userOrderId
        if (parts.length < 3) {
            throw ProtocolException.invalidFormat(
                "Cancel requires 3 fields: C, userId, orderId");
        }
        
        try {
            int userId = Integer.parseInt(parts[1].trim());
            int userOrderId = Integer.parseInt(parts[2].trim());
            
            return new Cancel(userId, userOrderId);
            
        } catch (NumberFormatException e) {
            throw ProtocolException.invalidField("number", e.getMessage());
        }
    }
    
    private InputMessage.TopOfBookQuery decodeQuery(String[] parts) throws ProtocolException {
        // Q, symbol
        if (parts.length < 2) {
            throw ProtocolException.invalidFormat(
                "Query requires 2 fields: Q, symbol");
        }
        
        Symbol symbol = Symbol.of(parts[1].trim());
        return new InputMessage.TopOfBookQuery(symbol);
    }
    
    @Override
    public void encodeOutput(OutputMessage message, ByteBuffer buffer) throws ProtocolException {
        String line = encodeOutputLine(message);
        buffer.put(line.getBytes(StandardCharsets.US_ASCII));
    }
    
    /**
     * Encode output message to CSV line.
     */
    public String encodeOutputLine(OutputMessage message) {
        if (message instanceof Ack ack) {
            return String.format("A, %d, %d, %s",
                ack.userId(), ack.userOrderId(), ack.symbol());
        } else if (message instanceof CancelAck cancel) {
            return String.format("X, %d, %d, %s",
                cancel.userId(), cancel.userOrderId(), cancel.symbol());
        } else if (message instanceof Trade trade) {
            return String.format("T, %s, %d, %d, %d, %d, %d, %d",
                trade.symbol(),
                trade.buyUserId(), trade.buyUserOrderId(),
                trade.sellUserId(), trade.sellUserOrderId(),
                trade.price(), trade.quantity());
        } else if (message instanceof TopOfBookUpdate tob) {
            if (tob.eliminated()) {
                return String.format("B, %s, %c, -, -",
                    tob.symbol(), (char) tob.side().wire());
            } else {
                return String.format("B, %s, %c, %d, %d",
                    tob.symbol(), (char) tob.side().wire(), tob.price(), tob.quantity());
            }
        } else {
            return "# unknown message type";
        }
    }
}
