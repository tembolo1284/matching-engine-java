package com.engine.transport;

import com.engine.core.MatchingEngine;
import com.engine.messages.*;
import com.engine.transport.MessageRouter.RouteResult;
import com.engine.transport.MessageRouter.UnicastTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Central engine task that processes all trading requests.
 * 
 * <p>Runs in a dedicated thread, processing requests from a bounded queue
 * and routing outputs back to clients.
 * 
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Single-threaded engine processing (no locking needed)</li>
 *   <li>Bounded input queue for backpressure</li>
 *   <li>Pre-allocated output buffer (reused across all messages)</li>
 *   <li>Non-blocking output routing</li>
 * </ul>
 */
public final class EngineTask implements Runnable {
    
    private final BlockingQueue<EngineRequest> requestQueue;
    private final ClientRegistry registry;
    private final BlockingQueue<OutputMessage> multicastQueue;
    private final Metrics metrics;
    private final MatchingEngine engine;
    
    /** Pre-allocated output buffer (Power of Ten Rule 3). */
    private final List<OutputMessage> outputs;
    
    /** Shutdown flag. */
    private volatile boolean running = true;
    
    /**
     * Create a new engine task.
     * 
     * @param requestQueue bounded queue for incoming requests
     * @param registry client registry for routing responses
     * @param multicastQueue queue for multicast publisher (may be null)
     * @param metrics server metrics
     */
    public EngineTask(
            BlockingQueue<EngineRequest> requestQueue,
            ClientRegistry registry,
            BlockingQueue<OutputMessage> multicastQueue,
            Metrics metrics) {
        
        this.requestQueue = requestQueue;
        this.registry = registry;
        this.multicastQueue = multicastQueue;
        this.metrics = metrics;
        this.engine = new MatchingEngine();
        this.outputs = new ArrayList<>(64);
    }
    
    /**
     * Pre-register symbols before starting.
     */
    public void registerSymbols(String... symbols) {
        engine.registerSymbols(symbols);
    }
    
    /**
     * Signal shutdown.
     */
    public void shutdown() {
        running = false;
    }
    
    @Override
    public void run() {
        System.err.println("Engine task: started");
        
        while (running) {
            try {
                // Poll with timeout to allow shutdown checks
                EngineRequest request = requestQueue.poll(100, TimeUnit.MILLISECONDS);
                
                if (request == null) {
                    continue;
                }
                
                processRequest(request);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Engine task error: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        System.err.println("Engine task: shutting down");
    }
    
    /**
     * Process a single request.
     */
    private void processRequest(EngineRequest request) {
        metrics.messagesReceived.increment();
        
        // Track user → client mapping for response routing
        if (request.userId() != 0) {
            registry.setUserId(request.clientId(), request.userId());
        }
        
        // Update specific metrics based on message type
        switch (request.message()) {
            case NewOrder order -> metrics.ordersReceived.increment();
            case Cancel cancel -> metrics.cancelsReceived.increment();
            default -> {}
        }
        
        // Process in engine (reuse output buffer)
        outputs.clear();
        engine.process(request.message(), outputs);
        metrics.messagesProcessed.increment();
        
        // Route each output message
        for (OutputMessage msg : outputs) {
            routeOutput(msg, request.clientId());
        }
    }
    
    /**
     * Route an output message to appropriate recipients.
     */
    private void routeOutput(OutputMessage msg, ClientId originatingClient) {
        // Update trade count
        if (msg instanceof Trade) {
            metrics.tradesExecuted.increment();
        }
        
        // Route to appropriate recipients
        RouteResult result = MessageRouter.routeToOriginator(msg, originatingClient);
        
        // Send unicast messages
        for (UnicastTarget target : result.unicastTargets()) {
            if (registry.sendToClient(target.clientId(), target.message())) {
                metrics.messagesSent.increment();
            } else {
                metrics.sendErrors.increment();
            }
        }
        
        // Send to multicast if applicable
        if (result.shouldMulticast() && multicastQueue != null) {
            if (!multicastQueue.offer(msg)) {
                metrics.channelFullDrops.increment();
            }
        }
    }
}
