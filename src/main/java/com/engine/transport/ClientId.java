package com.engine.transport;

import java.util.concurrent.atomic.AtomicLong;

public record ClientId(long id) {
    private static final AtomicLong COUNTER = new AtomicLong(0);
    
    public static ClientId next() {
        return new ClientId(COUNTER.incrementAndGet());
    }
    
    @Override
    public String toString() {
        return "Client(" + id + ")";
    }
}
