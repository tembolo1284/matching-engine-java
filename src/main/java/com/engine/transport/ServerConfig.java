package com.engine.transport;

public final class ServerConfig {
    private int tcpPort = 1234;
    private boolean multicastEnabled = true;
    private String multicastGroup = "239.255.1.1";
    private int multicastPort = 5555;
    private int maxClients = 1024;
    private int channelCapacity = 100_000;
    private int clientQueueCapacity = 10_000;
    
    public static ServerConfig fromEnv() {
        ServerConfig config = new ServerConfig();
        String val;
        if ((val = System.getenv("ENGINE_TCP_PORT")) != null) config.tcpPort = Integer.parseInt(val);
        if ((val = System.getenv("ENGINE_MCAST_ENABLED")) != null) config.multicastEnabled = Boolean.parseBoolean(val);
        if ((val = System.getenv("ENGINE_MCAST_GROUP")) != null) config.multicastGroup = val;
        if ((val = System.getenv("ENGINE_MCAST_PORT")) != null) config.multicastPort = Integer.parseInt(val);
        if ((val = System.getenv("ENGINE_MAX_TCP_CLIENTS")) != null) config.maxClients = Integer.parseInt(val);
        if ((val = System.getenv("ENGINE_CHANNEL_CAPACITY")) != null) config.channelCapacity = Integer.parseInt(val);
        return config;
    }
    
    public ServerConfig parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tcp-port" -> tcpPort = Integer.parseInt(args[++i]);
                case "--no-mcast" -> multicastEnabled = false;
                case "--mcast-group" -> multicastGroup = args[++i];
                case "--mcast-port" -> multicastPort = Integer.parseInt(args[++i]);
                case "--max-clients" -> maxClients = Integer.parseInt(args[++i]);
                default -> {}
            }
        }
        return this;
    }
    
    public int tcpPort() { return tcpPort; }
    public boolean multicastEnabled() { return multicastEnabled; }
    public String multicastGroup() { return multicastGroup; }
    public int multicastPort() { return multicastPort; }
    public int maxClients() { return maxClients; }
    public int channelCapacity() { return channelCapacity; }
    public int clientQueueCapacity() { return clientQueueCapacity; }
}
