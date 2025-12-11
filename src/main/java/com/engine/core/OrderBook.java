package com.engine.core;

import com.engine.messages.*;
import com.engine.types.Side;
import com.engine.types.Symbol;
import com.engine.types.TopOfBookSnapshot;

import java.util.ArrayList;
import java.util.List;

public final class OrderBook {
    
    private final Symbol symbol;
    private final List<PriceLevel> bids = new ArrayList<>(64);
    private final List<PriceLevel> asks = new ArrayList<>(64);
    private TopOfBookSnapshot prevTob = TopOfBookSnapshot.EMPTY;
    
    public OrderBook(Symbol symbol) {
        this.symbol = symbol;
    }
    
    public Symbol symbol() { return symbol; }
    
    public void addOrder(Order order, List<OutputMessage> outputs) {
        outputs.add(Ack.of(symbol, order.userId(), order.userOrderId()));
        matchOrder(order, outputs);
        if (!order.isFilled()) {
            addToBook(order);
        }
        emitTobChanges(outputs);
    }
    
    public boolean cancelOrder(Order order, List<OutputMessage> outputs) {
        List<PriceLevel> levels = order.side() == Side.BUY ? bids : asks;
        for (PriceLevel level : levels) {
            if (level.price() == order.price() && level.removeOrder(order)) {
                outputs.add(CancelAck.of(symbol, order.userId(), order.userOrderId()));
                if (level.isEmpty()) levels.remove(level);
                emitTobChanges(outputs);
                return true;
            }
        }
        return false;
    }
    
    public void flush() {
        bids.clear();
        asks.clear();
        prevTob = TopOfBookSnapshot.EMPTY;
    }
    
    public TopOfBookSnapshot getTopOfBook() {
        int bidPrice = 0, bidQty = 0, askPrice = 0, askQty = 0;
        if (!bids.isEmpty()) {
            bidPrice = bids.get(0).price();
            bidQty = bids.get(0).totalQuantity();
        }
        if (!asks.isEmpty()) {
            askPrice = asks.get(0).price();
            askQty = asks.get(0).totalQuantity();
        }
        return new TopOfBookSnapshot(bidPrice, bidQty, askPrice, askQty);
    }
    
    private void matchOrder(Order aggressor, List<OutputMessage> outputs) {
        List<PriceLevel> passiveLevels = aggressor.side() == Side.BUY ? asks : bids;
        
        while (!aggressor.isFilled() && !passiveLevels.isEmpty()) {
            PriceLevel best = passiveLevels.get(0);
            if (!aggressor.canMatch(best.price())) break;
            
            while (!aggressor.isFilled() && !best.isEmpty()) {
                Order passive = best.front();
                int fillQty = Math.min(aggressor.remainingQty(), passive.remainingQty());
                aggressor.fill(fillQty);
                passive.fill(fillQty);
                best.updateQuantity(fillQty);
                
                int buyUser, buyOrder, sellUser, sellOrder;
                if (aggressor.side() == Side.BUY) {
                    buyUser = aggressor.userId(); buyOrder = aggressor.userOrderId();
                    sellUser = passive.userId(); sellOrder = passive.userOrderId();
                } else {
                    buyUser = passive.userId(); buyOrder = passive.userOrderId();
                    sellUser = aggressor.userId(); sellOrder = aggressor.userOrderId();
                }
                outputs.add(Trade.of(symbol, buyUser, buyOrder, sellUser, sellOrder, best.price(), fillQty));
                
                if (passive.isFilled()) best.removeFilledFromFront();
            }
            if (best.isEmpty()) passiveLevels.remove(0);
        }
    }
    
    private void addToBook(Order order) {
        List<PriceLevel> levels = order.side() == Side.BUY ? bids : asks;
        boolean ascending = order.side() == Side.SELL;
        
        int insertIdx = 0;
        for (PriceLevel level : levels) {
            if (level.price() == order.price()) {
                level.addOrder(order);
                return;
            }
            if (ascending ? order.price() < level.price() : order.price() > level.price()) break;
            insertIdx++;
        }
        PriceLevel newLevel = new PriceLevel(order.price());
        newLevel.addOrder(order);
        levels.add(insertIdx, newLevel);
    }
    
    private void emitTobChanges(List<OutputMessage> outputs) {
        TopOfBookSnapshot current = getTopOfBook();
        if (prevTob.bidChanged(current)) {
            outputs.add(current.hasBid() 
                ? TopOfBookUpdate.of(symbol, Side.BUY, current.bidPrice(), current.bidQty())
                : TopOfBookUpdate.eliminated(symbol, Side.BUY));
        }
        if (prevTob.askChanged(current)) {
            outputs.add(current.hasAsk()
                ? TopOfBookUpdate.of(symbol, Side.SELL, current.askPrice(), current.askQty())
                : TopOfBookUpdate.eliminated(symbol, Side.SELL));
        }
        prevTob = current;
    }
}
