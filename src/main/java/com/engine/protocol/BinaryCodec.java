package com.engine.protocol;

import com.engine.messages.*;
import com.engine.types.Side;
import com.engine.types.Symbol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import static com.engine.protocol.WireConstants.*;

/**
 * Binary codec for low-latency protocol.
 * 
 * <p>All integers are big-endian. Messages start with magic byte (0x4D).
 */
public final class BinaryCodec implements Codec {
    
    public static final BinaryCodec INSTANCE = new BinaryCodec();
    
    private BinaryCodec() {}
    
    @Override
    public Type type() {
        return Type.BINARY;
    }
    
    @Override
    public Optional<InputMessage> decodeInput(ByteBuffer buffer) throws ProtocolException {
        buffer.order(ByteOrder.BIG_ENDIAN);
        
        if (buffer.remaining() < 2) {
            throw ProtocolException.truncated("need at least 2 bytes");
        }
        
        byte magic = buffer.get();
        if (magic != MAGIC_BYTE) {
            throw ProtocolException.invalidMagic(magic);
        }
        
        byte type = buffer.get();
        
        if (type == INPUT_NEW_ORDER) {
            return Optional.of(decodeNewOrder(buffer));
        } else if (type == INPUT_CANCEL) {
            return Optional.of(decodeCancel(buffer));
        } else if (type == INPUT_FLUSH) {
            return Optional.of(InputMessage.FLUSH);
        } else if (type == INPUT_QUERY) {
            return Optional.of(decodeQuery(buffer));
        } else {
            throw ProtocolException.unknownMessageType(type);
        }
    }
    
    private NewOrder decodeNewOrder(ByteBuffer buffer) throws ProtocolException {
        // Already read magic and type (2 bytes)
        // Need: userId(4) + symbol(8) + price(4) + qty(4) + side(1) + orderId(4) = 25 bytes
        if (buffer.remaining() < 25) {
            throw ProtocolException.truncated("NewOrder needs 25 more bytes");
        }
        
        int userId = buffer.getInt();
        Symbol symbol = Symbol.fromBuffer(buffer);
        int price = buffer.getInt();
        int qty = buffer.getInt();
        Side side = Side.fromWire(buffer.get());
        int userOrderId = buffer.getInt();
        
        return new NewOrder(userId, userOrderId, symbol, price, qty, side);
    }
    
    private Cancel decodeCancel(ByteBuffer buffer) throws ProtocolException {
        // Already read magic and type (2 bytes)
        // Need: userId(4) + symbol(8) + orderId(4) = 16 bytes
        if (buffer.remaining() < 16) {
            throw ProtocolException.truncated("Cancel needs 16 more bytes");
        }
        
        int userId = buffer.getInt();
        // Skip symbol (8 bytes) - not needed for cancel
        buffer.position(buffer.position() + SYMBOL_SIZE);
        int userOrderId = buffer.getInt();
        
        return new Cancel(userId, userOrderId);
    }
    
    private InputMessage.TopOfBookQuery decodeQuery(ByteBuffer buffer) throws ProtocolException {
        // Already read magic and type (2 bytes)
        // Need: symbol(8) = 8 bytes
        if (buffer.remaining() < SYMBOL_SIZE) {
            throw ProtocolException.truncated("Query needs 8 more bytes");
        }
        
        Symbol symbol = Symbol.fromBuffer(buffer);
        return new InputMessage.TopOfBookQuery(symbol);
    }
    
    @Override
    public void encodeOutput(OutputMessage message, ByteBuffer buffer) throws ProtocolException {
        buffer.order(ByteOrder.BIG_ENDIAN);
        
        if (message instanceof Ack ack) {
            encodeAck(ack, buffer);
        } else if (message instanceof CancelAck cancel) {
            encodeCancelAck(cancel, buffer);
        } else if (message instanceof Trade trade) {
            encodeTrade(trade, buffer);
        } else if (message instanceof TopOfBookUpdate tob) {
            encodeTopOfBook(tob, buffer);
        }
    }
    
    private void encodeAck(Ack ack, ByteBuffer buffer) {
        buffer.put(MAGIC_BYTE);
        buffer.put(OUTPUT_ACK);
        ack.symbol().toBuffer(buffer);
        buffer.putInt(ack.userId());
        buffer.putInt(ack.userOrderId());
    }
    
    private void encodeCancelAck(CancelAck cancel, ByteBuffer buffer) {
        buffer.put(MAGIC_BYTE);
        buffer.put(OUTPUT_CANCEL_ACK);
        cancel.symbol().toBuffer(buffer);
        buffer.putInt(cancel.userId());
        buffer.putInt(cancel.userOrderId());
    }
    
    private void encodeTrade(Trade trade, ByteBuffer buffer) {
        buffer.put(MAGIC_BYTE);
        buffer.put(OUTPUT_TRADE);
        trade.symbol().toBuffer(buffer);
        buffer.putInt(trade.buyUserId());
        buffer.putInt(trade.buyUserOrderId());
        buffer.putInt(trade.sellUserId());
        buffer.putInt(trade.sellUserOrderId());
        buffer.putInt(trade.price());
        buffer.putInt(trade.quantity());
    }
    
    private void encodeTopOfBook(TopOfBookUpdate tob, ByteBuffer buffer) {
        buffer.put(MAGIC_BYTE);
        buffer.put(OUTPUT_TOP_OF_BOOK);
        tob.symbol().toBuffer(buffer);
        buffer.put(tob.side().wire());
        buffer.putInt(tob.price());
        buffer.putInt(tob.quantity());
        buffer.put((byte) 0); // padding
    }
}
