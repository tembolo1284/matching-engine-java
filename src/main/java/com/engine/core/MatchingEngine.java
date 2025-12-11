package com.engine.core;

import com.engine.messages.*;
import com.engine.types.Symbol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-symbol matching engine.
 */
public final class MatchingEngine {
    
    private static final int MAX_SYMBOLS = 100_000;
    
    // Symbol -> OrderBook
    private final Map<Symbol, OrderBook> orderBooks;
    
    // (userId, userOrderId) -> Symbol (for cancel lookups)
    private final Map<Long, Symbol> orderToSymbol;
    
    // (userId, userOrderId) -> Order (for cancel lookups)
    private final Map<Long, Order> orderRegistry;
    
    // Monotonic timestamp counter (simulated nanoseconds)
    private long timestampCounter;
    
    public MatchingEngine() {
        this.orderBooks = new HashMap<>(64);
        this.orderToSymbol = new HashMap<>(1024);
        this.orderRegistry = new HashMap<>(1024);
        this.timestampCounter = 0;
    }
    
    /**
     * Pre-register symbols (creates order books).
     */
    public void registerSymbols(String... symbols) {
        for (String s : symbols) {
            registerSymbol(Symbol.of(s));
        }
    }
    
    /**
     * Pre-register a symbol.
     */
    public void registerSymbol(Symbol symbol) {
        assert symbol != null : "symbol is null";
        
        if (!orderBooks.containsKey(symbol) && orderBooks.size() < MAX_SYMBOLS) {
            orderBooks.put(symbol, new OrderBook(symbol));
        }
    }
    
    /**
     * Process an input message.
     * 
     * @param message input message
     * @param outputs output buffer (cleared and filled by this method)
     */
    public void process(InputMessage message, List<OutputMessage> outputs) {
        assert message != null : "message is null";
        assert outputs != null : "outputs is null";
        
        outputs.clear();
        
        if (message instanceof NewOrder order) {
            processNewOrder(order, outputs);
        } else if (message instanceof Cancel cancel) {
            processCancel(cancel, outputs);
        } else if (message instanceof InputMessage.Flush) {
            processFlush(outputs);
        } else if (message instanceof InputMessage.TopOfBookQuery query) {
            processQuery(query, outputs);
        }
    }
    
    private void processNewOrder(NewOrder msg, List<OutputMessage> outputs) {
        // Get or create order book
        OrderBook book = orderBooks.get(msg.symbol());
        if (book == null) {
            registerSymbol(msg.symbol());
            book = orderBooks.get(msg.symbol());
        }
        
        assert book != null : "failed to create order book";
        
        // Create order
        Order order = new Order();
        order.initialize(
            msg.userId(),
            msg.userOrderId(),
            msg.symbol(),
            msg.price(),
            msg.quantity(),
            msg.side(),
            nextTimestamp()
        );
        
        // Register for cancel lookups
        long key = msg.packedKey();
        orderToSymbol.put(key, msg.symbol());
        orderRegistry.put(key, order);
        
        // Process in order book
        book.addOrder(order, outputs);
        
        // Unregister if fully filled
        if (order.isFilled()) {
            orderToSymbol.remove(key);
            orderRegistry.remove(key);
        }
    }
    
    private void processCancel(Cancel msg, List<OutputMessage> outputs) {
        long key = msg.packedKey();
        
        // Look up symbol from order registry
        Symbol symbol = orderToSymbol.get(key);
        Order order = orderRegistry.get(key);
        
        if (symbol == null || order == null) {
            // Order not found - still ack the cancel
            outputs.add(CancelAck.of(Symbol.UNKNOWN, msg.userId(), msg.userOrderId()));
            return;
        }
        
        OrderBook book = orderBooks.get(symbol);
        if (book == null) {
            outputs.add(CancelAck.of(symbol, msg.userId(), msg.userOrderId()));
            return;
        }
        
        // Try to cancel
        boolean cancelled = book.cancelOrder(order, outputs);
        
        if (!cancelled) {
            // Order already filled or not found
            outputs.add(CancelAck.of(symbol, msg.userId(), msg.userOrderId()));
        }
        
        // Always unregister
        orderToSymbol.remove(key);
        orderRegistry.remove(key);
    }
    
    private void processFlush(List<OutputMessage> outputs) {
        for (OrderBook book : orderBooks.values()) {
            book.flush();
        }
        orderToSymbol.clear();
        orderRegistry.clear();
    }
    
    private void processQuery(InputMessage.TopOfBookQuery query, List<OutputMessage> outputs) {
        OrderBook book = orderBooks.get(query.symbol());
        if (book == null) {
            return;
        }
        
        var tob = book.getTopOfBook();
        
        if (tob.hasBid()) {
            outputs.add(TopOfBookUpdate.of(query.symbol(), 
                com.engine.types.Side.BUY, tob.bidPrice(), tob.bidQty()));
        }
        
        if (tob.hasAsk()) {
            outputs.add(TopOfBookUpdate.of(query.symbol(), 
                com.engine.types.Side.SELL, tob.askPrice(), tob.askQty()));
        }
    }
    
    private long nextTimestamp() {
        return ++timestampCounter;
    }
    
    /**
     * Get order book for symbol (for testing/inspection).
     */
    public OrderBook getOrderBook(Symbol symbol) {
        return orderBooks.get(symbol);
    }
}
