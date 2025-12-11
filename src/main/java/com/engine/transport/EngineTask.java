package com.engine.transport;

import com.engine.core.MatchingEngine;
import com.engine.messages.*;
import com.engine.transport.MessageRouter.RouteResult;
import com.engine.transport.MessageRouter.UnicastTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class EngineTask implements Runnable {
    
    private final BlockingQueue<EngineRequest> requestQueue;
    private final ClientRegistry registry;
    private final BlockingQueue<OutputMessage> multicastQueue;
    private final Metrics metrics;
    private final MatchingEngine engine = new MatchingEngine();
    private final List<OutputMessage> outputs = new ArrayList<>(64);
    private volatile boolean running = true;
    
    public EngineTask(BlockingQueue<EngineRequest> requestQueue, ClientRegistry registry,
                      BlockingQueue<OutputMessage> multicastQueue, Metrics metrics) {
        this.requestQueue = requestQueue;
        this.registry = registry;
        this.multicastQueue = multicastQueue;
        this.metrics = metrics;
    }
    
    public void registerSymbols(String... symbols) {
        engine.registerSymbols(symbols);
    }
    
    public void shutdown() {
        running = false;
    }
    
    @Override
    public void run() {
        System.err.println("Engine task: started");
        while (running) {
            try {
                EngineRequest request = requestQueue.poll(100, TimeUnit.MILLISECONDS);
                if (request != null) processRequest(request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Engine task error: " + e.getMessage());
            }
        }
        System.err.println("Engine task: stopped");
    }
    
    private void processRequest(EngineRequest request) {
        metrics.messagesReceived.increment();
        
        if (request.userId() != 0) {
            registry.setUserId(request.clientId(), request.userId());
        }
        
        InputMessage message = request.message();
        if (message instanceof NewOrder) metrics.ordersReceived.increment();
        else if (message instanceof Cancel) metrics.cancelsReceived.increment();
        
        outputs.clear();
        engine.process(message, outputs);
        metrics.messagesProcessed.increment();
        
        for (OutputMessage msg : outputs) {
            if (msg instanceof Trade) metrics.tradesExecuted.increment();
            
            RouteResult result = MessageRouter.routeToOriginator(msg, request.clientId());
            
            for (UnicastTarget target : result.unicastTargets()) {
                if (registry.sendToClient(target.clientId(), target.message())) {
                    metrics.messagesSent.increment();
                } else {
                    metrics.sendErrors.increment();
                }
            }
            
            if (result.shouldMulticast() && multicastQueue != null) {
                if (!multicastQueue.offer(msg)) metrics.channelFullDrops.increment();
            }
        }
    }
}
