package com.engine.transport;

import com.engine.messages.OutputMessage;

import java.util.concurrent.*;

public final class EngineServer {
    
    private final ServerConfig config;
    private final Metrics metrics = new Metrics();
    private final BlockingQueue<EngineRequest> engineQueue;
    private final BlockingQueue<OutputMessage> multicastQueue;
    private final ClientRegistry registry;
    
    private EngineTask engineTask;
    private TcpServer tcpServer;
    private MulticastPublisher multicastPublisher;
    private Thread engineThread;
    private Thread tcpThread;
    private Thread multicastThread;
    
    public EngineServer(ServerConfig config) {
        this.config = config;
        this.engineQueue = new LinkedBlockingQueue<>(config.channelCapacity());
        this.multicastQueue = config.multicastEnabled() 
            ? new LinkedBlockingQueue<>(config.channelCapacity()) : null;
        this.registry = new ClientRegistry(config.clientQueueCapacity());
    }
    
    public void start() {
        printBanner();
        
        // Engine task
        engineTask = new EngineTask(engineQueue, registry, multicastQueue, metrics);
        engineTask.registerSymbols("IBM", "AAPL", "GOOG", "MSFT", "TSLA");
        engineThread = new Thread(engineTask, "engine");
        engineThread.start();
        
        // TCP server
        tcpServer = new TcpServer(config.tcpPort(), config.maxClients(), 
                                  engineQueue, registry, metrics);
        tcpThread = new Thread(tcpServer, "tcp-server");
        tcpThread.start();
        
        // Multicast publisher
        if (config.multicastEnabled()) {
            multicastPublisher = new MulticastPublisher(multicastQueue, 
                config.multicastGroup(), config.multicastPort(), metrics);
            multicastThread = new Thread(multicastPublisher, "multicast");
            multicastThread.start();
        }
        
        System.err.println("Server started");
    }
    
    public void shutdown() {
        System.err.println("Shutting down...");
        
        if (tcpServer != null) tcpServer.shutdown();
        if (multicastPublisher != null) multicastPublisher.shutdown();
        if (engineTask != null) engineTask.shutdown();
        
        try {
            if (tcpThread != null) tcpThread.join(1000);
            if (multicastThread != null) multicastThread.join(1000);
            if (engineThread != null) engineThread.join(1000);
        } catch (InterruptedException ignored) {}
        
        metrics.printSummary();
    }
    
    private void printBanner() {
        System.err.println("====================================");
        System.err.println("  Java Matching Engine v0.1.0");
        System.err.println("====================================");
        System.err.println("TCP Port:    " + config.tcpPort());
        System.err.println("Multicast:   " + (config.multicastEnabled() 
            ? config.multicastGroup() + ":" + config.multicastPort() : "disabled"));
        System.err.println("Max clients: " + config.maxClients());
        System.err.println("====================================");
    }
    
    public static void main(String[] args) {
        ServerConfig config = ServerConfig.fromEnv().parseArgs(args);
        EngineServer server = new EngineServer(config);
        
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        
        server.start();
        
        // Keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {}
    }
}
