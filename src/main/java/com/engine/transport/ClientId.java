package com.engine.transport;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Unique client identifier.
 * 
 * <p>Thread-safe atomic counter ensures unique IDs across all connections.
 */
public record ClientId(long id) implements Comparable<ClientId> {
    
    /** Counter for generating unique IDs. */
    private static final AtomicLong COUNTER = new AtomicLong(1);
    
    /**
     * Generate the next unique client ID.
     */
    public static ClientId next() {
        return new ClientId(COUNTER.getAndIncrement());
    }
    
    /**
     * Create a client ID with a specific value (for testing).
     */
    public static ClientId of(long id) {
        return new ClientId(id);
    }
    
    @Override
    public int compareTo(ClientId other) {
        return Long.compare(this.id, other.id);
    }
    
    @Override
    public String toString() {
        return "Client(" + id + ")";
    }
}
