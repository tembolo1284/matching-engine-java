package com.engine.transport;

import com.engine.messages.OutputMessage;

import java.io.IOException;
import java.util.concurrent.*;

/**
 * Main entry point for the matching engine server.
 * 
 * <p>Orchestrates:
 * <ul>
 *   <li>Engine task (message processing)</li>
 *   <li>TCP server (client connections)</li>
 *   <li>Multicast publisher (market data)</li>
 * </ul>
 * 
 * <h2>Usage</h2>
 * <pre>{@code
 * EngineServer server = new EngineServer(ServerConfig.fromEnv());
 * server.registerSymbols("IBM", "AAPL", "GOOG");
 * server.start(); // Blocks until shutdown
 * }</pre>
 */
public final class EngineServer {
    
    private final ServerConfig config;
    private final Metrics metrics;
    private final ClientRegistry registry;
    private final BlockingQueue<EngineRequest> engineQueue;
    private final BlockingQueue<OutputMessage> multicastQueue;
    
    private EngineTask engineTask;
    private TcpServer tcpServer;
    private MulticastPublisher multicastPublisher;
    
    private Thread engineThread;
    private Thread multicastThread;
    
    /**
     * Create a new engine server.
     */
    public EngineServer(ServerConfig config) {
        this.config = config;
        this.metrics = new Metrics();
        this.registry = new ClientRegistry(config.clientChannelCapacity());
        this.engineQueue = new LinkedBlockingQueue<>(config.engineChannelCapacity());
        this.multicastQueue = config.multicastEnabled() 
            ? new LinkedBlockingQueue<>(config.multicastChannelCapacity())
            : null;
    }
    
    /**
     * Pre-register symbols.
     */
    public EngineServer registerSymbols(String... symbols) {
        if (engineTask != null) {
            engineTask.registerSymbols(symbols);
        }
        return this;
    }
    
    /**
     * Start the server (blocks until shutdown).
     */
    public void start() throws IOException {
        printBanner();
        
        // Create engine task
        engineTask = new EngineTask(engineQueue, registry, multicastQueue, metrics);
        
        // Start engine thread
        engineThread = new Thread(engineTask, "engine-task");
        engineThread.start();
        
        // Start multicast publisher if enabled
        if (config.multicastEnabled() && multicastQueue != null) {
            multicastPublisher = new MulticastPublisher(config, multicastQueue, metrics);
            multicastThread = new Thread(multicastPublisher, "multicast-publisher");
            multicastThread.start();
        }
        
        // Setup shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown-hook"));
        
        // Start TCP server (blocks until shutdown)
        if (config.tcpEnabled()) {
            tcpServer = new TcpServer(config, registry, engineQueue, metrics);
            tcpServer.start();
        } else {
            // Just wait for interrupt
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Shutdown the server.
     */
    public void shutdown() {
        System.err.println();
        System.err.println("==============================================================");
        System.err.println("Shutting down...");
        System.err.println("==============================================================");
        
        // Stop accepting new connections
        if (tcpServer != null) {
            tcpServer.stop();
        }
        
        // Stop multicast publisher
        if (multicastPublisher != null) {
            multicastPublisher.shutdown();
        }
        
        // Stop engine task
        if (engineTask != null) {
            engineTask.shutdown();
        }
        
        // Wait for threads to finish
        try {
            if (engineThread != null) {
                engineThread.join(1000);
            }
            if (multicastThread != null) {
                multicastThread.join(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Print metrics
        metrics.printSummary();
        
        System.err.println("Goodbye!");
    }
    
    /**
     * Get metrics (for monitoring).
     */
    public Metrics metrics() {
        return metrics;
    }
    
    /**
     * Print startup banner.
     */
    private void printBanner() {
        System.err.println("==============================================================");
        System.err.println("         Java Matching Engine Server v0.1.0");
        System.err.println("==============================================================");
        System.err.println();
        System.err.println("Transports:");
        if (config.tcpEnabled()) {
            System.err.println("  TCP:       " + config.tcpAddress() + " (CSV, Binary)");
        }
        if (config.udpEnabled()) {
            System.err.println("  UDP:       " + config.udpAddress() + " (CSV, Binary)");
        }
        if (config.multicastEnabled()) {
            System.err.println("  Multicast: " + config.multicastAddress() + " (Binary)");
        }
        System.err.println();
        System.err.println("Limits:");
        System.err.println("  Max TCP clients:    " + config.maxTcpClients());
        System.err.println("  Engine queue:       " + config.engineChannelCapacity());
        System.err.println("  Client queue:       " + config.clientChannelCapacity());
        System.err.println();
        System.err.println("==============================================================");
        System.err.println("Ready. Press Ctrl+C to shutdown.");
        System.err.println("==============================================================");
    }
    
    // =========================================================================
    // Main Entry Point
    // =========================================================================
    
    public static void main(String[] args) {
        try {
            ServerConfig config = ServerConfig.fromEnv().parseArgs(args);
            
            EngineServer server = new EngineServer(config);
            server.registerSymbols("IBM", "AAPL", "GOOG", "MSFT", "TSLA");
            server.start();
            
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
