package com.engine.messages;

/**
 * Cancel order request message.
 * 
 * <p>Design Principles:
 * <ul>
 *   <li>Minimal payload: only userId + userOrderId needed</li>
 *   <li>Symbol not required: engine tracks order-to-symbol mapping internally</li>
 *   <li>CancelAck response will include the resolved symbol</li>
 * </ul>
 * 
 * <p>Size: 8 bytes (userId:4 + userOrderId:4).
 * 
 * <p>Wire Format (CSV): {@code C, userId, userOrderId}
 * <p>Wire Format (Binary): {@code [type:1][userId:4][userOrderId:4]}
 */
public record Cancel(
    int userId,
    int userOrderId
) implements InputMessage {
    
    /**
     * Canonical constructor with validation.
     * 
     * <p>Power of Ten Rule 5: Assertions for invariants.
     * <p>Power of Ten Rule 7: Validate all parameters.
     */
    public Cancel {
        assert userId >= 0 : "userId must be non-negative";
        assert userOrderId >= 0 : "userOrderId must be non-negative";
    }
    
    /**
     * Static factory method for clearer call sites.
     */
    public static Cancel of(int userId, int userOrderId) {
        return new Cancel(userId, userOrderId);
    }
    
    @Override
    public Type type() {
        return Type.CANCEL;
    }
    
    /**
     * Pack userId and userOrderId into a single long key.
     * 
     * <p>Useful for HashMap lookup in order-to-symbol tracking.
     * Format: [userId:32][userOrderId:32]
     */
    public long packedKey() {
        return ((long) userId << 32) | (userOrderId & 0xFFFFFFFFL);
    }
    
    /**
     * Extract userId from a packed key.
     */
    public static int unpackUserId(long packedKey) {
        return (int) (packedKey >>> 32);
    }
    
    /**
     * Extract userOrderId from a packed key.
     */
    public static int unpackUserOrderId(long packedKey) {
        return (int) packedKey;
    }
    
    @Override
    public String toString() {
        // For logging only, not hot path
        return String.format("Cancel[user=%d, orderId=%d]", userId, userOrderId);
    }
}
