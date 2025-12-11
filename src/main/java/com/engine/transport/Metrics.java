package com.engine.transport;

import java.util.concurrent.atomic.LongAdder;

public final class Metrics {
    public final LongAdder messagesReceived = new LongAdder();
    public final LongAdder messagesProcessed = new LongAdder();
    public final LongAdder messagesSent = new LongAdder();
    public final LongAdder ordersReceived = new LongAdder();
    public final LongAdder tradesExecuted = new LongAdder();
    public final LongAdder cancelsReceived = new LongAdder();
    public final LongAdder sendErrors = new LongAdder();
    public final LongAdder decodeErrors = new LongAdder();
    public final LongAdder channelFullDrops = new LongAdder();
    
    public void printSummary() {
        System.err.println("=== Metrics ===");
        System.err.println("Messages received:  " + messagesReceived.sum());
        System.err.println("Messages processed: " + messagesProcessed.sum());
        System.err.println("Messages sent:      " + messagesSent.sum());
        System.err.println("Orders received:    " + ordersReceived.sum());
        System.err.println("Trades executed:    " + tradesExecuted.sum());
        System.err.println("Cancels received:   " + cancelsReceived.sum());
        System.err.println("Send errors:        " + sendErrors.sum());
        System.err.println("Decode errors:      " + decodeErrors.sum());
        System.err.println("Channel full drops: " + channelFullDrops.sum());
    }
}
