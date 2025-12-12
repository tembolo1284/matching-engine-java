package com.engine.core;

import com.engine.messages.*;
import com.engine.types.Side;
import com.engine.types.Symbol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MatchingEngine {

    private final Map<Symbol, OrderBook> orderBooks = new HashMap<>(64);
    private final Map<Long, Symbol> orderToSymbol = new HashMap<>(1024);
    private final Map<Long, Order> orderRegistry = new HashMap<>(1024);
    private long timestampCounter = 0;

    public void registerSymbols(String... symbols) {
        for (String s : symbols) {
            Symbol sym = Symbol.of(s);
            if (!orderBooks.containsKey(sym)) {
                orderBooks.put(sym, new OrderBook(sym));
            }
        }
    }

    public void process(InputMessage message, List<OutputMessage> outputs) {
        outputs.clear();
        switch (message) {
            case NewOrder msg -> processNewOrder(msg, outputs);
            case Cancel msg -> processCancel(msg, outputs);
            case InputMessage.Flush ignored -> processFlush(outputs);
            case InputMessage.TopOfBookQuery q -> processQuery(q, outputs);
        }
    }

    private void processNewOrder(NewOrder msg, List<OutputMessage> outputs) {
        OrderBook book = orderBooks.computeIfAbsent(msg.symbol(), OrderBook::new);

        Order order = new Order();
        order.initialize(msg.userId(), msg.userOrderId(), msg.symbol(),
                        msg.price(), msg.quantity(), msg.side(), ++timestampCounter);

        long key = msg.packedKey();
        orderToSymbol.put(key, msg.symbol());
        orderRegistry.put(key, order);

        book.addOrder(order, outputs);

        if (order.isFilled()) {
            orderToSymbol.remove(key);
            orderRegistry.remove(key);
        }
    }

    private void processCancel(Cancel msg, List<OutputMessage> outputs) {
        long key = msg.packedKey();
        Symbol symbol = orderToSymbol.get(key);
        Order order = orderRegistry.get(key);

        if (symbol == null || order == null) {
            outputs.add(CancelAck.of(Symbol.UNKNOWN, msg.userId(), msg.userOrderId()));
            return;
        }

        OrderBook book = orderBooks.get(symbol);
        if (book == null || !book.cancelOrder(order, outputs)) {
            outputs.add(CancelAck.of(symbol, msg.userId(), msg.userOrderId()));
        }

        orderToSymbol.remove(key);
        orderRegistry.remove(key);
    }

    private void processFlush(List<OutputMessage> outputs) {
        for (OrderBook book : orderBooks.values()) {
            book.flush(outputs);
        }
        orderToSymbol.clear();
        orderRegistry.clear();
    }

    private void processQuery(InputMessage.TopOfBookQuery query, List<OutputMessage> outputs) {
        OrderBook book = orderBooks.get(query.symbol());
        if (book == null) return;
        var tob = book.getTopOfBook();
        if (tob.hasBid()) outputs.add(TopOfBookUpdate.of(query.symbol(), Side.BUY, tob.bidPrice(), tob.bidQty()));
        if (tob.hasAsk()) outputs.add(TopOfBookUpdate.of(query.symbol(), Side.SELL, tob.askPrice(), tob.askQty()));
    }

    public OrderBook getOrderBook(Symbol symbol) {
        return orderBooks.get(symbol);
    }
}
