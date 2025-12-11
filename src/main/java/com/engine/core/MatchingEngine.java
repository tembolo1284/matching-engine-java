package com.engine.core;

import com.engine.messages.*;
import com.engine.types.Side;
import com.engine.types.Symbol;
import com.engine.types.TopOfBookSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-symbol matching engine.
 * 
 * <h2>Architecture</h2>
 * <ul>
 *   <li>Pre-registered symbols with pre-created order books</li>
 *   <li>Order-to-symbol tracking via HashMap for cancel routing</li>
 *   <li>Output buffer passed by caller (no allocation on hot path)</li>
 *   <li>Monotonic timestamp counter for time priority</li>
 * </ul>
 * 
 * <h2>Power of Ten Compliance</h2>
 * <ul>
 *   <li>Rule 1: No recursion, simple control flow</li>
 *   <li>Rule 2: Bounded iterations in all loops</li>
 *   <li>Rule 3: Pre-allocated structures, reusable output buffer</li>
 *   <li>Rule 4: Methods kept short and focused</li>
 *   <li>Rule 5: Assertions verify invariants</li>
 *   <li>Rule 7: All parameters validated, return values checked</li>
 * </ul>
 * 
 * <h2>Thread Safety</h2>
 * <p>This class is NOT thread-safe. For concurrent access, use external
 * synchronization or a single-threaded event loop (recommended for latency).
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * MatchingEngine engine = new MatchingEngine(EngineConfig.defaults());
 * engine.registerSymbol(Symbol.of("IBM"));
 * engine.registerSymbol(Symbol.of("AAPL"));
 * 
 * List<OutputMessage> outputs = new ArrayList<>(64);
 * 
 * // Process orders (reuse output buffer)
 * engine.process(NewOrder.of(1, 1, "IBM", 100, 10, Side.BUY), outputs);
 * // handle outputs...
 * outputs.clear();
 * 
 * engine.process(NewOrder.of(2, 1, "IBM", 100, 10, Side.SELL), outputs);
 * // outputs now contains: Ack, Trade, TopOfBook updates
 * }</pre>
 */
public final class MatchingEngine {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    /** Symbol used when cancel target is unknown. */
    public static final Symbol UNKNOWN_SYMBOL = Symbol.of("<UNK>");
    
    /** Maximum registered symbols (Power of Ten Rule 2). */
    private static final int MAX_SYMBOLS = 100_000;
    
    // =========================================================================
    // Configuration
    // =========================================================================
    
    /**
     * Engine configuration.
     */
    public record EngineConfig(
        int numSymbols,
        int maxOrders,
        int levelsPerSide,
        int outputBufferCapacity
    ) {
        public EngineConfig {
            assert numSymbols > 0 : "numSymbols must be positive";
            assert maxOrders > 0 : "maxOrders must be positive";
            assert levelsPerSide > 0 : "levelsPerSide must be positive";
            assert outputBufferCapacity > 0 : "outputBufferCapacity must be positive";
        }
        
        public static EngineConfig defaults() {
            return new EngineConfig(
                1024,       // numSymbols
                1_000_000,  // maxOrders
                256,        // levelsPerSide
                1024        // outputBufferCapacity
            );
        }
        
        public static EngineConfig small() {
            return new EngineConfig(
                64,         // numSymbols
                10_000,     // maxOrders
                64,         // levelsPerSide
                256         // outputBufferCapacity
            );
        }
    }
    
    // =========================================================================
    // Fields
    // =========================================================================
    
    /** Symbol -> OrderBook mapping. */
    private final Map<Symbol, OrderBook> orderBooks;
    
    /** (userId, userOrderId) packed as long -> Symbol for cancel routing. */
    private final Map<Long, Symbol> orderToSymbol;
    
    /** Configuration. */
    private final EngineConfig config;
    
    /** Timestamp counter for time priority (monotonically increasing). */
    private long timestampCounter;
    
    // =========================================================================
    // Constructors
    // =========================================================================
    
    /**
     * Create a new engine with default configuration.
     */
    public MatchingEngine() {
        this(EngineConfig.defaults());
    }
    
    /**
     * Create a new engine with custom configuration.
     */
    public MatchingEngine(EngineConfig config) {
        assert config != null : "Config cannot be null";
        
        this.config = config;
        this.orderBooks = new HashMap<>(config.numSymbols());
        this.orderToSymbol = new HashMap<>(config.maxOrders());
        this.timestampCounter = 0;
    }
    
    // =========================================================================
    // Symbol Registration
    // =========================================================================
    
    /**
     * Pre-register a symbol (creates order book upfront).
     * 
     * <p>Call at startup for all known symbols to avoid allocation during trading.
     */
    public void registerSymbol(Symbol symbol) {
        assert symbol != null : "Symbol cannot be null";
        
        if (!orderBooks.containsKey(symbol)) {
            assert orderBooks.size() < MAX_SYMBOLS : "Exceeded max symbols";
            OrderBook book = new OrderBook(symbol, config.levelsPerSide());
            orderBooks.put(symbol, book);
        }
    }
    
    /**
     * Pre-register multiple symbols.
     */
    public void registerSymbols(Iterable<Symbol> symbols) {
        assert symbols != null : "Symbols cannot be null";
        
        for (Symbol symbol : symbols) {
            registerSymbol(symbol);
        }
    }
    
    /**
     * Pre-register symbols from string names.
     */
    public void registerSymbols(String... symbolNames) {
        for (String name : symbolNames) {
            registerSymbol(Symbol.of(name));
        }
    }
    
    // =========================================================================
    // Message Processing (Main API)
    // =========================================================================
    
    /**
     * Process a single input message, writing outputs to the provided list.
     * 
     * <p>This is the primary API for the hot path. Caller should reuse
     * the output list across calls to avoid allocation.
     * 
     * @param msg the input message to process
     * @param outputs list to append output messages (caller should clear between calls)
     */
    public void process(InputMessage msg, List<OutputMessage> outputs) {
        assert msg != null : "Message cannot be null";
        assert outputs != null : "Outputs list cannot be null";
        
        switch (msg) {
            case NewOrder newOrder -> processNewOrder(newOrder, outputs);
            case Cancel cancel -> processCancel(cancel, outputs);
            case Flush flush -> processFlush(outputs);
            case TopOfBookQuery query -> processTopOfBookQuery(query, outputs);
        }
    }
    
    /**
     * Convenience: process message and return new list.
     * 
     * <p>Note: Allocates new ArrayList. Use {@link #process(InputMessage, List)}
     * with reusable buffer for hot path.
     */
    public List<OutputMessage> process(InputMessage msg) {
        List<OutputMessage> outputs = new ArrayList<>(config.outputBufferCapacity());
        process(msg, outputs);
        return outputs;
    }
    
    // =========================================================================
    // Message Handlers
    // =========================================================================
    
    private void processNewOrder(NewOrder msg, List<OutputMessage> outputs) {
        assert msg.quantity() > 0 : "NewOrder with zero quantity";
        
        Symbol symbol = msg.symbol();
        long orderKey = packOrderKey(msg.userId(), msg.userOrderId());
        
        // Generate timestamp
        long timestamp = nextTimestamp();
        
        // Get or create order book
        OrderBook book = orderBooks.get(symbol);
        if (book == null) {
            // Auto-create book for unknown symbol
            // In strict mode, could reject instead
            assert orderBooks.size() < MAX_SYMBOLS : "Exceeded max symbols";
            book = new OrderBook(symbol, config.levelsPerSide());
            orderBooks.put(symbol, book);
        }
        
        // Process the order
        book.addOrder(msg, timestamp, outputs);
        
        // Track order -> symbol mapping for cancels (limit orders only)
        if (msg.isLimit()) {
            orderToSymbol.put(orderKey, symbol);
        }
    }
    
    private void processCancel(Cancel msg, List<OutputMessage> outputs) {
        long orderKey = packOrderKey(msg.userId(), msg.userOrderId());
        
        // Find which symbol this order belongs to
        Symbol symbol = orderToSymbol.get(orderKey);
        
        if (symbol != null) {
            // Route to the correct book
            OrderBook book = orderBooks.get(symbol);
            if (book != null) {
                book.cancelOrder(msg.userId(), msg.userOrderId(), outputs);
            } else {
                // Book doesn't exist (shouldn't happen)
                outputs.add(OutputMessage.cancelAck(msg.userId(), msg.userOrderId(), symbol));
            }
            
            // Remove from tracking
            orderToSymbol.remove(orderKey);
        } else {
            // Order not found - still emit CancelAck with unknown symbol
            outputs.add(OutputMessage.cancelAck(msg.userId(), msg.userOrderId(), UNKNOWN_SYMBOL));
        }
    }
    
    private void processFlush(List<OutputMessage> outputs) {
        int iterations = 0;
        
        // Flush each order book
        for (OrderBook book : orderBooks.values()) {
            if (iterations++ >= MAX_SYMBOLS) {
                assert false : "Exceeded max symbols";
                break;
            }
            book.flush(outputs);
        }
        
        // Clear order tracking
        orderToSymbol.clear();
        
        // Note: Keep orderBooks (don't clear) so symbols stay registered
    }
    
    private void processTopOfBookQuery(TopOfBookQuery query, List<OutputMessage> outputs) {
        Symbol symbol = query.symbol();
        OrderBook book = orderBooks.get(symbol);
        
        int bidPrice, bidQty, askPrice, askQty;
        
        if (book != null) {
            bidPrice = book.bestBidPrice();
            bidQty = book.bestBidQuantity();
            askPrice = book.bestAskPrice();
            askQty = book.bestAskQuantity();
        } else {
            bidPrice = 0;
            bidQty = 0;
            askPrice = 0;
            askQty = 0;
        }
        
        // Emit bid side
        if (bidPrice == 0) {
            outputs.add(OutputMessage.topOfBookEliminated(symbol, Side.BUY));
        } else {
            outputs.add(OutputMessage.topOfBook(symbol, Side.BUY, bidPrice, bidQty));
        }
        
        // Emit ask side
        if (askPrice == 0) {
            outputs.add(OutputMessage.topOfBookEliminated(symbol, Side.SELL));
        } else {
            outputs.add(OutputMessage.topOfBook(symbol, Side.SELL, askPrice, askQty));
        }
    }
    
    // =========================================================================
    // Query API
    // =========================================================================
    
    /**
     * Get a reference to an order book by symbol.
     */
    public OrderBook getBook(Symbol symbol) {
        assert symbol != null : "Symbol cannot be null";
        return orderBooks.get(symbol);
    }
    
    /**
     * Get top-of-book snapshot for a symbol.
     */
    public TopOfBookSnapshot topOfBook(Symbol symbol) {
        assert symbol != null : "Symbol cannot be null";
        OrderBook book = orderBooks.get(symbol);
        return book != null ? book.topOfBook() : TopOfBookSnapshot.EMPTY;
    }
    
    /**
     * Get top-of-book snapshot for a symbol by name.
     */
    public TopOfBookSnapshot topOfBook(String symbolName) {
        return topOfBook(Symbol.of(symbolName));
    }
    
    /**
     * Number of registered symbols.
     */
    public int numSymbols() {
        return orderBooks.size();
    }
    
    /**
     * Number of tracked orders (for cancel routing).
     */
    public int numTrackedOrders() {
        return orderToSymbol.size();
    }
    
    /**
     * Get the current timestamp counter value.
     */
    public long currentTimestamp() {
        return timestampCounter;
    }
    
    // =========================================================================
    // Timestamp Management
    // =========================================================================
    
    /**
     * Set the timestamp counter (for deterministic testing).
     */
    public void setTimestamp(long ts) {
        assert ts >= 0 : "Timestamp must be non-negative";
        this.timestampCounter = ts;
    }
    
    private long nextTimestamp() {
        return timestampCounter++;
    }
    
    // =========================================================================
    // Utility
    // =========================================================================
    
    /**
     * Pack userId and userOrderId into a single long key.
     */
    private static long packOrderKey(int userId, int userOrderId) {
        return ((long) userId << 32) | (userOrderId & 0xFFFFFFFFL);
    }
    
    // =========================================================================
    // Object Methods
    // =========================================================================
    
    @Override
    public String toString() {
        return String.format("MatchingEngine[symbols=%d, trackedOrders=%d, ts=%d]",
            orderBooks.size(), orderToSymbol.size(), timestampCounter);
    }
}
