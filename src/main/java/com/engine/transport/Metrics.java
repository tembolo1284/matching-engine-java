package com.engine.transport;

import java.util.concurrent.atomic.LongAdder;

/**
 * Server-wide metrics and statistics.
 * 
 * <p>Uses {@link LongAdder} for high-throughput concurrent updates.
 */
public final class Metrics {
    
    // === Connection Metrics ===
    public final LongAdder tcpConnectionsTotal = new LongAdder();
    public final LongAdder tcpConnectionsActive = new LongAdder();
    public final LongAdder udpClientsActive = new LongAdder();
    
    // === Message Metrics ===
    public final LongAdder messagesReceived = new LongAdder();
    public final LongAdder messagesProcessed = new LongAdder();
    public final LongAdder messagesSent = new LongAdder();
    public final LongAdder multicastMessages = new LongAdder();
    
    // === Error Metrics ===
    public final LongAdder decodeErrors = new LongAdder();
    public final LongAdder sendErrors = new LongAdder();
    public final LongAdder channelFullDrops = new LongAdder();
    
    // === Engine Metrics ===
    public final LongAdder ordersReceived = new LongAdder();
    public final LongAdder tradesExecuted = new LongAdder();
    public final LongAdder cancelsReceived = new LongAdder();
    
    /**
     * Print summary statistics.
     */
    public void printSummary() {
        System.err.println("==============================================================");
        System.err.println("Server Metrics Summary");
        System.err.println("==============================================================");
        System.err.println("Connections:");
        System.err.println("  TCP total:      " + tcpConnectionsTotal.sum());
        System.err.println("  TCP active:     " + tcpConnectionsActive.sum());
        System.err.println("  UDP clients:    " + udpClientsActive.sum());
        System.err.println();
        System.err.println("Messages:");
        System.err.println("  Received:       " + messagesReceived.sum());
        System.err.println("  Processed:      " + messagesProcessed.sum());
        System.err.println("  Sent:           " + messagesSent.sum());
        System.err.println("  Multicast:      " + multicastMessages.sum());
        System.err.println();
        System.err.println("Trading:");
        System.err.println("  Orders:         " + ordersReceived.sum());
        System.err.println("  Trades:         " + tradesExecuted.sum());
        System.err.println("  Cancels:        " + cancelsReceived.sum());
        System.err.println();
        System.err.println("Errors:");
        System.err.println("  Decode errors:  " + decodeErrors.sum());
        System.err.println("  Send errors:    " + sendErrors.sum());
        System.err.println("  Channel drops:  " + channelFullDrops.sum());
        System.err.println("==============================================================");
    }
    
    /**
     * Reset all counters to zero.
     */
    public void reset() {
        tcpConnectionsTotal.reset();
        tcpConnectionsActive.reset();
        udpClientsActive.reset();
        messagesReceived.reset();
        messagesProcessed.reset();
        messagesSent.reset();
        multicastMessages.reset();
        decodeErrors.reset();
        sendErrors.reset();
        channelFullDrops.reset();
        ordersReceived.reset();
        tradesExecuted.reset();
        cancelsReceived.reset();
    }
}
