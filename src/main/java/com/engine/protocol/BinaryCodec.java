package com.engine.protocol;

import com.engine.messages.*;
import com.engine.types.Side;
import com.engine.types.Symbol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import static com.engine.protocol.WireConstants.*;

public final class BinaryCodec implements Codec {
    
    public static final BinaryCodec INSTANCE = new BinaryCodec();
    private BinaryCodec() {}
    
    @Override
    public Type type() { return Type.BINARY; }
    
    @Override
    public Optional<InputMessage> decodeInput(ByteBuffer buffer) throws ProtocolException {
        buffer.order(ByteOrder.BIG_ENDIAN);
        if (buffer.remaining() < 2) throw ProtocolException.truncated("need 2 bytes");
        
        byte magic = buffer.get();
        if (magic != MAGIC) throw ProtocolException.invalidMagic(magic);
        
        byte type = buffer.get();
        return Optional.of(switch (type) {
            case TYPE_NEW_ORDER -> decodeNewOrder(buffer);
            case TYPE_CANCEL -> decodeCancel(buffer);
            case TYPE_FLUSH -> InputMessage.FLUSH;
            case TYPE_QUERY -> new InputMessage.TopOfBookQuery(Symbol.fromBuffer(buffer));
            default -> throw ProtocolException.unknownType(type);
        });
    }
    
    private NewOrder decodeNewOrder(ByteBuffer buf) throws ProtocolException {
        if (buf.remaining() < 25) throw ProtocolException.truncated("NewOrder needs 25 bytes");
        int userId = buf.getInt();
        Symbol symbol = Symbol.fromBuffer(buf);
        int price = buf.getInt();
        int qty = buf.getInt();
        Side side = Side.fromWire(buf.get());
        int orderId = buf.getInt();
        return new NewOrder(userId, orderId, symbol, price, qty, side);
    }
    
    private Cancel decodeCancel(ByteBuffer buf) throws ProtocolException {
        if (buf.remaining() < 16) throw ProtocolException.truncated("Cancel needs 16 bytes");
        int userId = buf.getInt();
        buf.position(buf.position() + SYMBOL_SIZE); // skip symbol
        int orderId = buf.getInt();
        return new Cancel(userId, orderId);
    }
    
    @Override
    public void encodeOutput(OutputMessage message, ByteBuffer buffer) {
        buffer.order(ByteOrder.BIG_ENDIAN);
        switch (message) {
            case Ack a -> {
                buffer.put(MAGIC).put(TYPE_ACK);
                a.symbol().toBuffer(buffer);
                buffer.putInt(a.userId()).putInt(a.userOrderId());
            }
            case CancelAck c -> {
                buffer.put(MAGIC).put(TYPE_CANCEL_ACK);
                c.symbol().toBuffer(buffer);
                buffer.putInt(c.userId()).putInt(c.userOrderId());
            }
            case Trade t -> {
                buffer.put(MAGIC).put(TYPE_TRADE);
                t.symbol().toBuffer(buffer);
                buffer.putInt(t.buyUserId()).putInt(t.buyUserOrderId())
                      .putInt(t.sellUserId()).putInt(t.sellUserOrderId())
                      .putInt(t.price()).putInt(t.quantity());
            }
            case TopOfBookUpdate b -> {
                buffer.put(MAGIC).put(TYPE_TOB);
                b.symbol().toBuffer(buffer);
                buffer.put(b.side().wire()).putInt(b.price()).putInt(b.quantity()).put((byte) 0);
            }
        }
    }
}
