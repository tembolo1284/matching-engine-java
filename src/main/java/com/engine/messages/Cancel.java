package com.engine.messages;

public record Cancel(int userId, int userOrderId) implements InputMessage {
    
    public long packedKey() {
        return ((long) userId << 32) | (userOrderId & 0xFFFFFFFFL);
    }
}
