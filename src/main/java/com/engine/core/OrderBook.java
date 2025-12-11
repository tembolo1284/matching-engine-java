package com.engine.core;

import com.engine.messages.*;
import com.engine.types.OrderType;
import com.engine.types.Side;
import com.engine.types.Symbol;
import com.engine.types.TopOfBookSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-symbol order book with price-time priority matching.
 * 
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Price levels stored in sorted ArrayList for cache-friendly iteration</li>
 *   <li>Bids: descending by price (best bid at index 0)</li>
 *   <li>Asks: ascending by price (best ask at index 0)</li>
 *   <li>Outputs written to caller-provided list (no allocation)</li>
 *   <li>Top-of-book cached for change detection</li>
 * </ul>
 * 
 * <h2>Power of Ten Compliance</h2>
 * <ul>
 *   <li>Rule 1: No recursion, simple control flow</li>
 *   <li>Rule 2: All loops bounded with MAX constants</li>
 *   <li>Rule 3: Pre-allocated capacity, reusable output buffer</li>
 *   <li>Rule 4: Methods kept short and focused</li>
 *   <li>Rule 5: Assertions verify invariants</li>
 *   <li>Rule 7: All parameters validated</li>
 * </ul>
 */
public final class OrderBook {
    
    // =========================================================================
    // Constants (Power of Ten Rule 2 - bounded loops)
    // =========================================================================
    
    /** Maximum iterations for matching loop. */
    private static final int MAX_MATCH_ITERATIONS = 100_000;
    
    /** Maximum price levels per side. */
    private static final int MAX_PRICE_LEVELS = 10_000;
    
    /** Default price level capacity per side. */
    private static final int DEFAULT_LEVELS_CAPACITY = 256;
    
    // =========================================================================
    // Fields
    // =========================================================================
    
    /** Symbol for this book. */
    private final Symbol symbol;
    
    /** Bid price levels, sorted descending (best bid at index 0). */
    private final ArrayList<PriceLevel> bids;
    
    /** Ask price levels, sorted ascending (best ask at index 0). */
    private final ArrayList<PriceLevel> asks;
    
    /** Previous top-of-book snapshot for change detection. */
    private TopOfBookSnapshot prevTob;
    
    // =========================================================================
    // Constructors
    // =========================================================================
    
    /**
     * Create a new order book for the given symbol.
     */
    public OrderBook(Symbol symbol) {
        this(symbol, DEFAULT_LEVELS_CAPACITY);
    }
    
    /**
     * Create a new order book with specified capacity.
     * 
     * @param symbol the symbol for this book
     * @param levelsCapacity initial capacity for price levels per side
     */
    public OrderBook(Symbol symbol, int levelsCapacity) {
        assert symbol != null : "Symbol cannot be null";
        assert levelsCapacity > 0 : "Levels capacity must be positive";
        
        this.symbol = symbol;
        this.bids = new ArrayList<>(levelsCapacity);
        this.asks = new ArrayList<>(levelsCapacity);
        this.prevTob = TopOfBookSnapshot.EMPTY;
    }
    
    // =========================================================================
    // Accessors
    // =========================================================================
    
    public Symbol symbol() {
        return symbol;
    }
    
    public int bidLevels() {
        return bids.size();
    }
    
    public int askLevels() {
        return asks.size();
    }
    
    public int bestBidPrice() {
        return bids.isEmpty() ? 0 : bids.get(0).price();
    }
    
    public int bestAskPrice() {
        return asks.isEmpty() ? 0 : asks.get(0).price();
    }
    
    public int bestBidQuantity() {
        return bids.isEmpty() ? 0 : bids.get(0).totalQuantity();
    }
    
    public int bestAskQuantity() {
        return asks.isEmpty() ? 0 : asks.get(0).totalQuantity();
    }
    
    /**
     * Get current top-of-book snapshot.
     */
    public TopOfBookSnapshot topOfBook() {
        return new TopOfBookSnapshot(
            bestBidPrice(),
            bestBidQuantity(),
            bestAskPrice(),
            bestAskQuantity()
        );
    }
    
    // =========================================================================
    // Order Processing
    // =========================================================================
    
    /**
     * Process a new order, writing outputs to the provided list.
     * 
     * <p>Outputs: Ack, Trades, TopOfBook changes.
     * 
     * @param msg the new order message
     * @param timestampNs timestamp for time priority
     * @param outputs list to append output messages (reuse across calls)
     */
    public void addOrder(NewOrder msg, long timestampNs, List<OutputMessage> outputs) {
        assert msg != null : "NewOrder cannot be null";
        assert msg.symbol().equals(symbol) : "Order symbol mismatch";
        assert msg.quantity() > 0 : "Order quantity must be positive";
        assert outputs != null : "Outputs list cannot be null";
        
        // Create internal order
        Order order = new Order(
            msg.userId(),
            msg.userOrderId(),
            symbol,
            msg.price(),
            msg.quantity(),
            msg.side(),
            timestampNs
        );
        
        // Ack immediately
        outputs.add(OutputMessage.ack(order.userId(), order.userOrderId(), symbol));
        
        // Match against opposing side
        matchOrder(order, outputs);
        
        // Add remainder to book if limit order with remaining qty
        if (order.remainingQty() > 0 && order.isLimit()) {
            addToBook(order);
        }
        
        // Emit TOB changes
        emitTobChanges(outputs);
    }
    
    /**
     * Cancel an order by (userId, userOrderId).
     * 
     * @param userId the user ID
     * @param userOrderId the user's order ID
     * @param outputs list to append output messages
     * @return true if order was found and removed
     */
    public boolean cancelOrder(int userId, int userOrderId, List<OutputMessage> outputs) {
        assert outputs != null : "Outputs list cannot be null";
        
        boolean found = removeOrder(userId, userOrderId);
        
        // Always emit CancelAck
        outputs.add(OutputMessage.cancelAck(userId, userOrderId, symbol));
        
        // Emit TOB changes if we removed something
        if (found) {
            emitTobChanges(outputs);
        }
        
        return found;
    }
    
    /**
     * Flush all orders from the book.
     * 
     * @param outputs list to append cancel acks and TOB eliminations
     */
    public void flush(List<OutputMessage> outputs) {
        assert outputs != null : "Outputs list cannot be null";
        
        // Cancel acks for all bid orders
        int iterations = 0;
        for (PriceLevel level : bids) {
            if (iterations++ >= MAX_PRICE_LEVELS) {
                assert false : "Exceeded max price levels";
                break;
            }
            emitCancelAcksForLevel(level, outputs);
        }
        
        // Cancel acks for all ask orders
        iterations = 0;
        for (PriceLevel level : asks) {
            if (iterations++ >= MAX_PRICE_LEVELS) {
                assert false : "Exceeded max price levels";
                break;
            }
            emitCancelAcksForLevel(level, outputs);
        }
        
        // TOB eliminated if there were orders
        if (!bids.isEmpty()) {
            outputs.add(OutputMessage.topOfBookEliminated(symbol, Side.BUY));
        }
        if (!asks.isEmpty()) {
            outputs.add(OutputMessage.topOfBookEliminated(symbol, Side.SELL));
        }
        
        // Clear
        bids.clear();
        asks.clear();
        prevTob = TopOfBookSnapshot.EMPTY;
    }
    
    // =========================================================================
    // Matching Logic
    // =========================================================================
    
    /**
     * Match an incoming order against the opposing side.
     * 
     * <p>Power of Ten Rule 2: Bounded outer and inner loops.
     */
    private void matchOrder(Order order, List<OutputMessage> outputs) {
        assert order.remainingQty() > 0 : "Matching fully filled order";
        
        ArrayList<PriceLevel> opposingSide = order.isBuy() ? asks : bids;
        
        int iterations = 0;
        
        // Outer loop: iterate through price levels
        while (iterations < MAX_MATCH_ITERATIONS) {
            iterations++;
            
            if (order.remainingQty() == 0 || opposingSide.isEmpty()) {
                break;
            }
            
            int bestPrice = opposingSide.get(0).price();
            
            // Check if order can match at this price
            if (!order.canMatch(bestPrice)) {
                break;
            }
            
            // Match against orders at this level (FIFO)
            PriceLevel level = opposingSide.get(0);
            matchAtLevel(order, level, bestPrice, outputs);
            
            // Remove empty price level
            if (level.isEmpty()) {
                opposingSide.remove(0);
            }
        }
        
        assert iterations < MAX_MATCH_ITERATIONS : "Exceeded max match iterations";
    }
    
    /**
     * Match order against a single price level.
     * 
     * <p>Power of Ten Rule 2: Bounded iteration.
     */
    private void matchAtLevel(Order order, PriceLevel level, int tradePrice, 
                              List<OutputMessage> outputs) {
        
        List<Order> levelOrders = level.orders();
        int orderIdx = 0;
        int iterations = 0;
        
        while (iterations < PriceLevel.MAX_ORDERS_PER_LEVEL) {
            iterations++;
            
            if (order.remainingQty() == 0 || orderIdx >= levelOrders.size()) {
                break;
            }
            
            Order passive = levelOrders.get(orderIdx);
            int tradeQty = Math.min(order.remainingQty(), passive.remainingQty());
            
            assert tradeQty > 0 : "Zero trade quantity";
            
            // Emit trade (buyer info always first)
            emitTrade(order, passive, tradePrice, tradeQty, outputs);
            
            // Fill both orders
            order.fill(tradeQty);
            passive.fill(tradeQty);
            
            if (passive.isFilled()) {
                orderIdx++;
            }
        }
        
        assert iterations < PriceLevel.MAX_ORDERS_PER_LEVEL 
            : "Exceeded max orders per level";
        
        // Remove filled orders from front
        level.removeFilledFromFront();
    }
    
    /**
     * Emit a trade message with buyer/seller correctly ordered.
     */
    private void emitTrade(Order aggressor, Order passive, int price, int qty,
                          List<OutputMessage> outputs) {
        
        int buyUserId, buyOrderId, sellUserId, sellOrderId;
        
        if (aggressor.isBuy()) {
            buyUserId = aggressor.userId();
            buyOrderId = aggressor.userOrderId();
            sellUserId = passive.userId();
            sellOrderId = passive.userOrderId();
        } else {
            buyUserId = passive.userId();
            buyOrderId = passive.userOrderId();
            sellUserId = aggressor.userId();
            sellOrderId = aggressor.userOrderId();
        }
        
        outputs.add(OutputMessage.trade(
            symbol, buyUserId, buyOrderId, sellUserId, sellOrderId, price, qty));
    }
    
    // =========================================================================
    // Book Management
    // =========================================================================
    
    /**
     * Add a limit order to the appropriate side.
     * 
     * <p>Power of Ten Rule 2: Bounded binary search.
     */
    private void addToBook(Order order) {
        assert order.remainingQty() > 0 : "Adding filled order to book";
        assert order.isLimit() : "Adding market order to book";
        assert order.price() > 0 : "Limit order with zero price";
        
        ArrayList<PriceLevel> levels = order.isBuy() ? bids : asks;
        boolean descending = order.isBuy(); // Bids: high to low, Asks: low to high
        
        // Find insertion point
        int pos = findInsertionPoint(levels, order.price(), descending);
        
        // Check if level exists at this price
        if (pos < levels.size() && levels.get(pos).price() == order.price()) {
            // Add to existing level
            levels.get(pos).addOrder(order);
        } else {
            // Insert new level
            assert levels.size() < MAX_PRICE_LEVELS : "Exceeded max price levels";
            
            PriceLevel newLevel = new PriceLevel(order.price());
            newLevel.addOrder(order);
            levels.add(pos, newLevel);
        }
    }
    
    /**
     * Find insertion point for a price in sorted level list.
     * 
     * <p>Power of Ten Rule 2: Bounded iteration (binary search).
     */
    private int findInsertionPoint(ArrayList<PriceLevel> levels, int price, 
                                   boolean descending) {
        int low = 0;
        int high = levels.size();
        int iterations = 0;
        int maxIterations = 32; // log2(MAX_PRICE_LEVELS) rounded up
        
        while (low < high && iterations < maxIterations) {
            iterations++;
            int mid = (low + high) >>> 1;
            int midPrice = levels.get(mid).price();
            
            boolean goLeft;
            if (descending) {
                // Descending: find first price < target
                goLeft = midPrice >= price;
            } else {
                // Ascending: find first price > target
                goLeft = midPrice <= price;
            }
            
            if (goLeft) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        
        // Adjust for exact match
        if (low > 0 && levels.get(low - 1).price() == price) {
            return low - 1;
        }
        
        return low;
    }
    
    /**
     * Remove an order by (userId, userOrderId) from either side.
     * 
     * @return true if found and removed
     */
    private boolean removeOrder(int userId, int userOrderId) {
        // Try bids first
        if (removeFromSide(bids, userId, userOrderId)) {
            return true;
        }
        // Then asks
        return removeFromSide(asks, userId, userOrderId);
    }
    
    /**
     * Remove an order from a specific side.
     * 
     * <p>Power of Ten Rule 2: Bounded iteration.
     */
    private boolean removeFromSide(ArrayList<PriceLevel> levels, 
                                   int userId, int userOrderId) {
        int iterations = 0;
        
        for (int i = 0; i < levels.size() && iterations < MAX_PRICE_LEVELS; i++) {
            iterations++;
            PriceLevel level = levels.get(i);
            
            if (level.removeOrder(userId, userOrderId)) {
                // Remove empty level
                if (level.isEmpty()) {
                    levels.remove(i);
                }
                return true;
            }
        }
        
        return false;
    }
    
    // =========================================================================
    // Output Helpers
    // =========================================================================
    
    /**
     * Emit TOB changes if state has changed.
     */
    private void emitTobChanges(List<OutputMessage> outputs) {
        TopOfBookSnapshot current = topOfBook();
        
        // Bid side changed?
        if (current.bidChanged(prevTob)) {
            if (current.bidPrice() == 0) {
                outputs.add(OutputMessage.topOfBookEliminated(symbol, Side.BUY));
            } else {
                outputs.add(OutputMessage.topOfBook(
                    symbol, Side.BUY, current.bidPrice(), current.bidQuantity()));
            }
        }
        
        // Ask side changed?
        if (current.askChanged(prevTob)) {
            if (current.askPrice() == 0) {
                outputs.add(OutputMessage.topOfBookEliminated(symbol, Side.SELL));
            } else {
                outputs.add(OutputMessage.topOfBook(
                    symbol, Side.SELL, current.askPrice(), current.askQuantity()));
            }
        }
        
        prevTob = current;
    }
    
    /**
     * Emit cancel acks for all orders at a level.
     */
    private void emitCancelAcksForLevel(PriceLevel level, List<OutputMessage> outputs) {
        int iterations = 0;
        for (int i = 0; i < level.orderCount() && iterations < PriceLevel.MAX_ORDERS_PER_LEVEL; i++) {
            iterations++;
            Order order = level.getOrder(i);
            outputs.add(OutputMessage.cancelAck(order.userId(), order.userOrderId(), symbol));
        }
    }
    
    // =========================================================================
    // Object Methods
    // =========================================================================
    
    @Override
    public String toString() {
        TopOfBookSnapshot tob = topOfBook();
        return String.format("OrderBook[%s, bids=%d levels, asks=%d levels, %s]",
            symbol, bids.size(), asks.size(), tob);
    }
}
