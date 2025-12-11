package com.engine.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A price level containing orders at a single price.
 * 
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Orders stored in ArrayList for cache-friendly sequential access</li>
 *   <li>FIFO ordering: new orders appended, oldest at index 0</li>
 *   <li>Pre-allocated capacity to reduce resizing on hot path</li>
 *   <li>Mutable: orders added/removed as fills and cancels occur</li>
 * </ul>
 * 
 * <h2>Power of Ten Compliance</h2>
 * <ul>
 *   <li>Rule 2: Bounded iteration with MAX_ORDERS_PER_LEVEL</li>
 *   <li>Rule 3: Pre-allocated list capacity</li>
 *   <li>Rule 5: Assertions verify invariants</li>
 * </ul>
 */
public final class PriceLevel {
    
    /** Maximum orders per price level (Power of Ten Rule 2). */
    public static final int MAX_ORDERS_PER_LEVEL = 10_000;
    
    /** Default initial capacity per level. */
    public static final int DEFAULT_CAPACITY = 64;
    
    /** Price for this level (immutable after creation). */
    private final int price;
    
    /** Orders at this price, in time priority (oldest first). */
    private final ArrayList<Order> orders;
    
    /**
     * Create a new price level with default capacity.
     * 
     * @param price the price for this level (must be > 0 for limit orders)
     */
    public PriceLevel(int price) {
        this(price, DEFAULT_CAPACITY);
    }
    
    /**
     * Create a new price level with specified capacity.
     * 
     * @param price the price for this level
     * @param initialCapacity initial order list capacity
     */
    public PriceLevel(int price, int initialCapacity) {
        assert price > 0 : "Price level must have positive price";
        assert initialCapacity > 0 : "Initial capacity must be positive";
        
        this.price = price;
        this.orders = new ArrayList<>(initialCapacity);
    }
    
    // =========================================================================
    // Accessors
    // =========================================================================
    
    /**
     * Get the price for this level.
     */
    public int price() {
        return price;
    }
    
    /**
     * Get the number of orders at this level.
     */
    public int orderCount() {
        return orders.size();
    }
    
    /**
     * Check if this level has no orders.
     */
    public boolean isEmpty() {
        return orders.isEmpty();
    }
    
    /**
     * Get total remaining quantity across all orders at this level.
     * 
     * <p>Power of Ten Rule 2: Bounded iteration.
     */
    public int totalQuantity() {
        int total = 0;
        int count = 0;
        
        // Bounded loop
        for (int i = 0; i < orders.size() && count < MAX_ORDERS_PER_LEVEL; i++) {
            total += orders.get(i).remainingQty();
            count++;
        }
        
        assert count < MAX_ORDERS_PER_LEVEL : "Exceeded max orders per level";
        return total;
    }
    
    /**
     * Get the order at the specified index.
     * 
     * @param index order index (0 = oldest/highest priority)
     * @return the order at that index
     */
    public Order getOrder(int index) {
        assert index >= 0 && index < orders.size() : "Index out of bounds";
        return orders.get(index);
    }
    
    /**
     * Get the first (oldest) order at this level.
     * 
     * @return the first order, or null if empty
     */
    public Order firstOrder() {
        return orders.isEmpty() ? null : orders.get(0);
    }
    
    // =========================================================================
    // Modification
    // =========================================================================
    
    /**
     * Add an order to this level (appended for time priority).
     * 
     * <p>Power of Ten Rule 2: Bounded check on level size.
     * <p>Power of Ten Rule 5: Assertions verify order matches level price.
     * 
     * @param order the order to add
     * @throws IllegalStateException if level is at maximum capacity
     */
    public void addOrder(Order order) {
        assert order != null : "Order cannot be null";
        assert order.price() == this.price : "Order price must match level price";
        assert order.isActive() : "Cannot add filled order to level";
        
        if (orders.size() >= MAX_ORDERS_PER_LEVEL) {
            throw new IllegalStateException(
                "Price level at maximum capacity: " + MAX_ORDERS_PER_LEVEL);
        }
        
        orders.add(order);
    }
    
    /**
     * Remove the order at the specified index.
     * 
     * @param index the index to remove
     * @return the removed order
     */
    public Order removeOrder(int index) {
        assert index >= 0 && index < orders.size() : "Index out of bounds";
        return orders.remove(index);
    }
    
    /**
     * Remove the first (oldest) order from this level.
     * 
     * @return the removed order, or null if empty
     */
    public Order removeFirst() {
        return orders.isEmpty() ? null : orders.remove(0);
    }
    
    /**
     * Remove filled orders from the front of the queue.
     * 
     * <p>Called after matching to clean up fully-filled orders.
     * Orders are removed from front (oldest) while they are filled.
     * 
     * <p>Power of Ten Rule 2: Bounded iteration.
     * 
     * @return number of orders removed
     */
    public int removeFilledFromFront() {
        int removed = 0;
        int iterations = 0;
        
        // Bounded loop
        while (!orders.isEmpty() 
               && orders.get(0).isFilled() 
               && iterations < MAX_ORDERS_PER_LEVEL) {
            orders.remove(0);
            removed++;
            iterations++;
        }
        
        assert iterations < MAX_ORDERS_PER_LEVEL : "Exceeded max iterations";
        return removed;
    }
    
    /**
     * Find and remove an order by (userId, userOrderId).
     * 
     * <p>Power of Ten Rule 2: Bounded iteration.
     * 
     * @param userId the user ID
     * @param userOrderId the user's order ID
     * @return true if order was found and removed
     */
    public boolean removeOrder(int userId, int userOrderId) {
        int iterations = 0;
        
        // Bounded loop - linear search (could optimize with index if needed)
        for (int i = 0; i < orders.size() && iterations < MAX_ORDERS_PER_LEVEL; i++) {
            Order order = orders.get(i);
            if (order.userId() == userId && order.userOrderId() == userOrderId) {
                orders.remove(i);
                return true;
            }
            iterations++;
        }
        
        assert iterations < MAX_ORDERS_PER_LEVEL : "Exceeded max iterations";
        return false;
    }
    
    /**
     * Clear all orders from this level.
     * 
     * @param consumer optional consumer called for each removed order (for cancel acks)
     */
    public void clear(java.util.function.Consumer<Order> consumer) {
        if (consumer != null) {
            int iterations = 0;
            for (Order order : orders) {
                if (iterations++ >= MAX_ORDERS_PER_LEVEL) {
                    assert false : "Exceeded max orders per level";
                    break;
                }
                consumer.accept(order);
            }
        }
        orders.clear();
    }
    
    /**
     * Clear all orders from this level.
     */
    public void clear() {
        clear(null);
    }
    
    // =========================================================================
    // Iteration Support
    // =========================================================================
    
    /**
     * Get the underlying order list for iteration.
     * 
     * <p>Warning: Returns mutable internal list. Use with care.
     * For read-only iteration, use {@link #orderCount()} and {@link #getOrder(int)}.
     */
    List<Order> orders() {
        return orders;
    }
    
    // =========================================================================
    // Object Methods
    // =========================================================================
    
    @Override
    public String toString() {
        return String.format("PriceLevel[price=%d, orders=%d, qty=%d]",
            price, orders.size(), totalQuantity());
    }
}
