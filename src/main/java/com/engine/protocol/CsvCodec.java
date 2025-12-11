package com.engine.protocol;

import com.engine.messages.*;
import com.engine.types.Side;
import com.engine.types.Symbol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class CsvCodec implements Codec {
    
    public static final CsvCodec INSTANCE = new CsvCodec();
    private CsvCodec() {}
    
    @Override
    public Type type() { return Type.CSV; }
    
    @Override
    public Optional<InputMessage> decodeInput(ByteBuffer buffer) throws ProtocolException {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return decodeInputLine(new String(bytes, StandardCharsets.US_ASCII).trim());
    }
    
    public Optional<InputMessage> decodeInputLine(String line) throws ProtocolException {
        if (line.isEmpty() || line.startsWith("#")) return Optional.empty();
        
        String[] parts = line.split(",");
        if (parts.length == 0) return Optional.empty();
        
        String type = parts[0].trim().toUpperCase();
        return Optional.of(switch (type) {
            case "N" -> decodeNewOrder(parts);
            case "C" -> decodeCancel(parts);
            case "F" -> InputMessage.FLUSH;
            case "Q" -> new InputMessage.TopOfBookQuery(Symbol.of(parts[1].trim()));
            default -> throw ProtocolException.unknownType((byte) type.charAt(0));
        });
    }
    
    private NewOrder decodeNewOrder(String[] parts) throws ProtocolException {
        if (parts.length < 7) throw ProtocolException.invalidFormat("NewOrder needs 7 fields");
        try {
            return new NewOrder(
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[6].trim()),
                Symbol.of(parts[2].trim()),
                Integer.parseInt(parts[3].trim()),
                Integer.parseInt(parts[4].trim()),
                Side.fromWire((byte) parts[5].trim().charAt(0))
            );
        } catch (Exception e) {
            throw ProtocolException.invalidFormat(e.getMessage());
        }
    }
    
    private Cancel decodeCancel(String[] parts) throws ProtocolException {
        if (parts.length < 3) throw ProtocolException.invalidFormat("Cancel needs 3 fields");
        return new Cancel(Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
    }
    
    @Override
    public void encodeOutput(OutputMessage message, ByteBuffer buffer) {
        buffer.put(encodeOutputLine(message).getBytes(StandardCharsets.US_ASCII));
    }
    
    public String encodeOutputLine(OutputMessage message) {
        return switch (message) {
            case Ack a -> "A, %d, %d, %s".formatted(a.userId(), a.userOrderId(), a.symbol());
            case CancelAck c -> "X, %d, %d, %s".formatted(c.userId(), c.userOrderId(), c.symbol());
            case Trade t -> "T, %s, %d, %d, %d, %d, %d, %d".formatted(
                t.symbol(), t.buyUserId(), t.buyUserOrderId(),
                t.sellUserId(), t.sellUserOrderId(), t.price(), t.quantity());
            case TopOfBookUpdate b -> b.eliminated()
                ? "B, %s, %c, -, -".formatted(b.symbol(), (char) b.side().wire())
                : "B, %s, %c, %d, %d".formatted(b.symbol(), (char) b.side().wire(), b.price(), b.quantity());
        };
    }
}
