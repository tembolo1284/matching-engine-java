package com.engine.protocol;

import com.engine.messages.*;
import com.engine.types.Side;
import com.engine.types.Symbol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import static com.engine.protocol.WireConstants.*;

/**
 * Binary protocol codec matching Rust/Zig wire format exactly.
 * 
 * <p>All integers are big-endian (network order).
 * 
 * <h2>Input Messages (Client → Server)</h2>
 * <pre>
 * NewOrder (27 bytes):
 *   [0]     magic (0x4D 'M')
 *   [1]     msg_type ('N' = 0x4E)
 *   [2-5]   user_id (u32 BE)
 *   [6-13]  symbol (8 bytes null-padded)
 *   [14-17] price (u32 BE)
 *   [18-21] quantity (u32 BE)
 *   [22]    side ('B' or 'S')
 *   [23-26] user_order_id (u32 BE)
 *
 * Cancel (18 bytes):
 *   [0]     magic (0x4D)
 *   [1]     msg_type ('C' = 0x43)
 *   [2-5]   user_id (u32 BE)
 *   [6-13]  symbol (8 bytes null-padded)
 *   [14-17] user_order_id (u32 BE)
 *
 * Flush (2 bytes):
 *   [0]     magic (0x4D)
 *   [1]     msg_type ('F' = 0x46)
 * </pre>
 * 
 * <h2>Output Messages (Server → Client)</h2>
 * <pre>
 * Ack (18 bytes):
 *   [0]     magic (0x4D)
 *   [1]     msg_type ('A' = 0x41)
 *   [2-9]   symbol (8 bytes null-padded)
 *   [10-13] user_id (u32 BE)
 *   [14-17] user_order_id (u32 BE)
 *
 * CancelAck (18 bytes):
 *   [0]     magic (0x4D)
 *   [1]     msg_type ('X' = 0x58)
 *   [2-9]   symbol (8 bytes null-padded)
 *   [10-13] user_id (u32 BE)
 *   [14-17] user_order_id (u32 BE)
 *
 * Trade (34 bytes):
 *   [0]     magic (0x4D)
 *   [1]     msg_type ('T' = 0x54)
 *   [2-9]   symbol (8 bytes)
 *   [10-13] buy_user_id (u32 BE)
 *   [14-17] buy_order_id (u32 BE)
 *   [18-21] sell_user_id (u32 BE)
 *   [22-25] sell_order_id (u32 BE)
 *   [26-29] price (u32 BE)
 *   [30-33] quantity (u32 BE)
 *
 * TopOfBook (20 bytes):
 *   [0]     magic (0x4D)
 *   [1]     msg_type ('B' = 0x42)
 *   [2-9]   symbol (8 bytes)
 *   [10]    side ('B' or 'S')
 *   [11-14] price (u32 BE)
 *   [15-18] quantity (u32 BE)
 *   [19]    padding (0)
 * </pre>
 */
public final class BinaryCodec implements Codec {
    
    /** Singleton instance (stateless codec). */
    public static final BinaryCodec INSTANCE = new BinaryCodec();
    
    /** Reusable byte array for symbol reading/writing. */
    private final byte[] symbolBuffer = new byte[SYMBOL_SIZE];
    
    private BinaryCodec() {}
    
    @Override
    public Type type() {
        return Type.BINARY;
    }
    
    // =========================================================================
    // Input Decoding (Client → Server)
    // =========================================================================
    
    @Override
    public Optional<InputMessage> decodeInput(ByteBuffer buffer) throws ProtocolException {
        buffer.order(ByteOrder.BIG_ENDIAN);
        
        // Need at least 2 bytes for header
        if (buffer.remaining() < 2) {
            return Optional.empty();
        }
        
        int startPos = buffer.position();
        
        byte magic = buffer.get(startPos);
        byte msgType = buffer.get(startPos + 1);
        
        // Validate magic
        if (magic != MAGIC_BYTE) {
            throw ProtocolException.invalidMagic(magic, MAGIC_BYTE);
        }
        
        // Get expected size
        int expectedSize = inputMessageSize(msgType);
        if (expectedSize < 0) {
            throw ProtocolException.unknownMessageType(msgType);
        }
        
        // Check if we have complete message
        if (buffer.remaining() < expectedSize) {
            return Optional.empty();
        }
        
        // Skip header (already validated)
        buffer.position(startPos + 2);
        
        InputMessage msg = switch (msgType) {
            case INPUT_NEW_ORDER -> decodeNewOrder(buffer);
            case INPUT_CANCEL -> decodeCancel(buffer);
            case INPUT_FLUSH -> Flush.INSTANCE;
            default -> throw ProtocolException.unknownMessageType(msgType);
        };
        
        return Optional.of(msg);
    }
    
    private InputMessage decodeNewOrder(ByteBuffer buffer) throws ProtocolException {
        // [2-5] user_id
        int userId = buffer.getInt();
        
        // [6-13] symbol
        buffer.get(symbolBuffer);
        Symbol symbol = readSymbol(symbolBuffer);
        
        // [14-17] price
        int price = buffer.getInt();
        
        // [18-21] quantity
        int quantity = buffer.getInt();
        
        // [22] side
        byte sideByte = buffer.get();
        Side side = decodeSide(sideByte);
        
        // [23-26] user_order_id
        int userOrderId = buffer.getInt();
        
        // Validate
        if (quantity <= 0) {
            throw ProtocolException.invalidField("quantity", "must be positive");
        }
        
        return NewOrder.of(userId, userOrderId, symbol, price, quantity, side);
    }
    
    private InputMessage decodeCancel(ByteBuffer buffer) throws ProtocolException {
        // [2-5] user_id
        int userId = buffer.getInt();
        
        // [6-13] symbol (present but not used in Cancel)
        buffer.get(symbolBuffer);
        
        // [14-17] user_order_id
        int userOrderId = buffer.getInt();
        
        return Cancel.of(userId, userOrderId);
    }
    
    // =========================================================================
    // Output Decoding (for clients)
    // =========================================================================
    
    @Override
    public Optional<OutputMessage> decodeOutput(ByteBuffer buffer) throws ProtocolException {
        buffer.order(ByteOrder.BIG_ENDIAN);
        
        if (buffer.remaining() < 2) {
            return Optional.empty();
        }
        
        int startPos = buffer.position();
        
        byte magic = buffer.get(startPos);
        byte msgType = buffer.get(startPos + 1);
        
        if (magic != MAGIC_BYTE) {
            throw ProtocolException.invalidMagic(magic, MAGIC_BYTE);
        }
        
        int expectedSize = outputMessageSize(msgType);
        if (expectedSize < 0) {
            throw ProtocolException.unknownMessageType(msgType);
        }
        
        if (buffer.remaining() < expectedSize) {
            return Optional.empty();
        }
        
        buffer.position(startPos + 2);
        
        OutputMessage msg = switch (msgType) {
            case OUTPUT_ACK -> decodeAck(buffer);
            case OUTPUT_CANCEL_ACK -> decodeCancelAck(buffer);
            case OUTPUT_TRADE -> decodeTrade(buffer);
            case OUTPUT_TOP_OF_BOOK -> decodeTopOfBook(buffer);
            default -> throw ProtocolException.unknownMessageType(msgType);
        };
        
        return Optional.of(msg);
    }
    
    private OutputMessage decodeAck(ByteBuffer buffer) {
        buffer.get(symbolBuffer);
        Symbol symbol = readSymbol(symbolBuffer);
        int userId = buffer.getInt();
        int userOrderId = buffer.getInt();
        return new Ack(userId, userOrderId, symbol);
    }
    
    private OutputMessage decodeCancelAck(ByteBuffer buffer) {
        buffer.get(symbolBuffer);
        Symbol symbol = readSymbol(symbolBuffer);
        int userId = buffer.getInt();
        int userOrderId = buffer.getInt();
        return new CancelAck(userId, userOrderId, symbol);
    }
    
    private OutputMessage decodeTrade(ByteBuffer buffer) {
        buffer.get(symbolBuffer);
        Symbol symbol = readSymbol(symbolBuffer);
        int buyUserId = buffer.getInt();
        int buyOrderId = buffer.getInt();
        int sellUserId = buffer.getInt();
        int sellOrderId = buffer.getInt();
        int price = buffer.getInt();
        int quantity = buffer.getInt();
        return new Trade(symbol, buyUserId, buyOrderId, sellUserId, sellOrderId, price, quantity);
    }
    
    private OutputMessage decodeTopOfBook(ByteBuffer buffer) throws ProtocolException {
        buffer.get(symbolBuffer);
        Symbol symbol = readSymbol(symbolBuffer);
        byte sideByte = buffer.get();
        Side side = decodeSide(sideByte);
        int price = buffer.getInt();
        int quantity = buffer.getInt();
        buffer.get(); // padding byte
        
        if (price == 0 && quantity == 0) {
            return TopOfBookUpdate.eliminated(symbol, side);
        } else {
            return TopOfBookUpdate.active(symbol, side, price, quantity);
        }
    }
    
    // =========================================================================
    // Input Encoding (for clients)
    // =========================================================================
    
    @Override
    public void encodeInput(InputMessage message, ByteBuffer buffer) throws ProtocolException {
        buffer.order(ByteOrder.BIG_ENDIAN);
        
        switch (message) {
            case NewOrder order -> encodeNewOrder(order, buffer);
            case Cancel cancel -> encodeCancel(cancel, buffer);
            case Flush flush -> encodeFlush(buffer);
            case TopOfBookQuery query -> encodeFlush(buffer); // Not in Zig protocol
        }
    }
    
    private void encodeNewOrder(NewOrder order, ByteBuffer buffer) throws ProtocolException {
        ensureCapacity(buffer, NEW_ORDER_SIZE);
        
        buffer.put(MAGIC_BYTE);
        buffer.put(INPUT_NEW_ORDER);
        buffer.putInt(order.userId());
        writeSymbol(order.symbol(), buffer);
        buffer.putInt(order.price());
        buffer.putInt(order.quantity());
        buffer.put(encodeSide(order.side()));
        buffer.putInt(order.userOrderId());
    }
    
    private void encodeCancel(Cancel cancel, ByteBuffer buffer) throws ProtocolException {
        ensureCapacity(buffer, CANCEL_SIZE);
        
        buffer.put(MAGIC_BYTE);
        buffer.put(INPUT_CANCEL);
        buffer.putInt(cancel.userId());
        
        // Symbol field (8 bytes of zeros)
        for (int i = 0; i < SYMBOL_SIZE; i++) {
            buffer.put((byte) 0);
        }
        
        buffer.putInt(cancel.userOrderId());
    }
    
    private void encodeFlush(ByteBuffer buffer) throws ProtocolException {
        ensureCapacity(buffer, FLUSH_SIZE);
        
        buffer.put(MAGIC_BYTE);
        buffer.put(INPUT_FLUSH);
    }
    
    // =========================================================================
    // Output Encoding (Server → Client)
    // =========================================================================
    
    @Override
    public void encodeOutput(OutputMessage message, ByteBuffer buffer) throws ProtocolException {
        buffer.order(ByteOrder.BIG_ENDIAN);
        
        switch (message) {
            case Ack ack -> encodeAck(ack, buffer);
            case CancelAck cancelAck -> encodeCancelAck(cancelAck, buffer);
            case Trade trade -> encodeTrade(trade, buffer);
            case TopOfBookUpdate tob -> encodeTopOfBook(tob, buffer);
        }
    }
    
    private void encodeAck(Ack ack, ByteBuffer buffer) throws ProtocolException {
        ensureCapacity(buffer, ACK_SIZE);
        
        buffer.put(MAGIC_BYTE);
        buffer.put(OUTPUT_ACK);
        writeSymbol(ack.symbol(), buffer);
        buffer.putInt(ack.userId());
        buffer.putInt(ack.userOrderId());
    }
    
    private void encodeCancelAck(CancelAck ack, ByteBuffer buffer) throws ProtocolException {
        ensureCapacity(buffer, CANCEL_ACK_SIZE);
        
        buffer.put(MAGIC_BYTE);
        buffer.put(OUTPUT_CANCEL_ACK);
        writeSymbol(ack.symbol(), buffer);
        buffer.putInt(ack.userId());
        buffer.putInt(ack.userOrderId());
    }
    
    private void encodeTrade(Trade trade, ByteBuffer buffer) throws ProtocolException {
        ensureCapacity(buffer, TRADE_SIZE);
        
        buffer.put(MAGIC_BYTE);
        buffer.put(OUTPUT_TRADE);
        writeSymbol(trade.symbol(), buffer);
        buffer.putInt(trade.buyUserId());
        buffer.putInt(trade.buyUserOrderId());
        buffer.putInt(trade.sellUserId());
        buffer.putInt(trade.sellUserOrderId());
        buffer.putInt(trade.price());
        buffer.putInt(trade.quantity());
    }
    
    private void encodeTopOfBook(TopOfBookUpdate tob, ByteBuffer buffer) throws ProtocolException {
        ensureCapacity(buffer, TOP_OF_BOOK_SIZE);
        
        buffer.put(MAGIC_BYTE);
        buffer.put(OUTPUT_TOP_OF_BOOK);
        writeSymbol(tob.symbol(), buffer);
        buffer.put(encodeSide(tob.side()));
        
        if (tob.isEliminated()) {
            buffer.putInt(0);
            buffer.putInt(0);
        } else {
            buffer.putInt(tob.price());
            buffer.putInt(tob.quantity());
        }
        
        buffer.put((byte) 0); // padding
    }
    
    // =========================================================================
    // Helpers
    // =========================================================================
    
    private Side decodeSide(byte b) throws ProtocolException {
        return switch (b) {
            case SIDE_BUY, (byte) 'b' -> Side.BUY;
            case SIDE_SELL, (byte) 's' -> Side.SELL;
            default -> throw ProtocolException.invalidField("side", 
                String.format("invalid byte 0x%02X", b));
        };
    }
    
    private byte encodeSide(Side side) {
        return side == Side.BUY ? SIDE_BUY : SIDE_SELL;
    }
    
    private Symbol readSymbol(byte[] bytes) {
        // Find null terminator
        int len = 0;
        for (int i = 0; i < SYMBOL_SIZE; i++) {
            if (bytes[i] == 0) break;
            len++;
        }
        return Symbol.fromBytes(bytes);
    }
    
    private void writeSymbol(Symbol symbol, ByteBuffer buffer) {
        symbol.toBytes(symbolBuffer, 0);
        buffer.put(symbolBuffer);
    }
    
    private void ensureCapacity(ByteBuffer buffer, int required) throws ProtocolException {
        if (buffer.remaining() < required) {
            throw ProtocolException.truncated(
                String.format("need %d bytes, have %d", required, buffer.remaining()));
        }
    }
}
