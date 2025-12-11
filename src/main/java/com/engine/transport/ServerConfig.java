package com.engine.transport;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * Server configuration.
 * 
 * <p>Supports loading from environment variables and command-line arguments.
 */
public final class ServerConfig {
    
    // === TCP Settings ===
    private String tcpBindAddr = "0.0.0.0";
    private int tcpPort = 1234;
    private boolean tcpEnabled = true;
    
    // === UDP Settings ===
    private String udpBindAddr = "0.0.0.0";
    private int udpPort = 1235;
    private boolean udpEnabled = true;
    
    // === Multicast Settings ===
    private InetAddress multicastGroup;
    private int multicastPort = 1236;
    private boolean multicastEnabled = true;
    private int multicastTtl = 1;
    
    // === Connection Limits ===
    private int maxTcpClients = 1024;
    private int maxUdpClients = 4096;
    
    // === Timeouts ===
    private Duration readTimeout = Duration.ofSeconds(30);
    private Duration writeTimeout = Duration.ofSeconds(5);
    private Duration idleTimeout = Duration.ofSeconds(300);
    
    // === Channel Capacities (bounded for backpressure) ===
    private int engineChannelCapacity = 100_000;
    private int clientChannelCapacity = 10_000;
    private int multicastChannelCapacity = 50_000;
    
    // === Buffer Sizes ===
    private int tcpReadBufferSize = 8192;
    private int udpBufferSize = 65536;
    
    /**
     * Create default configuration.
     */
    public ServerConfig() {
        try {
            this.multicastGroup = InetAddress.getByName("239.255.0.1");
        } catch (UnknownHostException e) {
            throw new RuntimeException("Invalid default multicast address", e);
        }
    }
    
    /**
     * Load configuration from environment variables.
     */
    public static ServerConfig fromEnv() {
        ServerConfig config = new ServerConfig();
        
        // TCP
        config.tcpBindAddr = getEnv("ENGINE_TCP_ADDR", config.tcpBindAddr);
        config.tcpPort = getEnvInt("ENGINE_TCP_PORT", config.tcpPort);
        config.tcpEnabled = getEnvBool("ENGINE_TCP_ENABLED", config.tcpEnabled);
        
        // UDP
        config.udpBindAddr = getEnv("ENGINE_UDP_ADDR", config.udpBindAddr);
        config.udpPort = getEnvInt("ENGINE_UDP_PORT", config.udpPort);
        config.udpEnabled = getEnvBool("ENGINE_UDP_ENABLED", config.udpEnabled);
        
        // Multicast
        String mcastGroup = getEnv("ENGINE_MCAST_GROUP", null);
        if (mcastGroup != null) {
            try {
                config.multicastGroup = InetAddress.getByName(mcastGroup);
            } catch (UnknownHostException e) {
                System.err.println("Invalid multicast group: " + mcastGroup);
            }
        }
        config.multicastPort = getEnvInt("ENGINE_MCAST_PORT", config.multicastPort);
        config.multicastEnabled = getEnvBool("ENGINE_MCAST_ENABLED", config.multicastEnabled);
        
        // Limits
        config.maxTcpClients = getEnvInt("ENGINE_MAX_TCP_CLIENTS", config.maxTcpClients);
        config.engineChannelCapacity = getEnvInt("ENGINE_CHANNEL_CAPACITY", config.engineChannelCapacity);
        
        return config;
    }
    
    /**
     * Parse command-line arguments (call after fromEnv).
     */
    public ServerConfig parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tcp-port" -> tcpPort = Integer.parseInt(args[++i]);
                case "--udp-port" -> udpPort = Integer.parseInt(args[++i]);
                case "--mcast-port" -> multicastPort = Integer.parseInt(args[++i]);
                case "--no-tcp" -> tcpEnabled = false;
                case "--no-udp" -> udpEnabled = false;
                case "--no-mcast" -> multicastEnabled = false;
                case "--help", "-h" -> {
                    printHelp();
                    System.exit(0);
                }
            }
        }
        return this;
    }
    
    // === Getters ===
    
    public String tcpBindAddr() { return tcpBindAddr; }
    public int tcpPort() { return tcpPort; }
    public boolean tcpEnabled() { return tcpEnabled; }
    
    public String udpBindAddr() { return udpBindAddr; }
    public int udpPort() { return udpPort; }
    public boolean udpEnabled() { return udpEnabled; }
    
    public InetAddress multicastGroup() { return multicastGroup; }
    public int multicastPort() { return multicastPort; }
    public boolean multicastEnabled() { return multicastEnabled; }
    public int multicastTtl() { return multicastTtl; }
    
    public int maxTcpClients() { return maxTcpClients; }
    public int maxUdpClients() { return maxUdpClients; }
    
    public Duration readTimeout() { return readTimeout; }
    public Duration writeTimeout() { return writeTimeout; }
    public Duration idleTimeout() { return idleTimeout; }
    
    public int engineChannelCapacity() { return engineChannelCapacity; }
    public int clientChannelCapacity() { return clientChannelCapacity; }
    public int multicastChannelCapacity() { return multicastChannelCapacity; }
    
    public int tcpReadBufferSize() { return tcpReadBufferSize; }
    public int udpBufferSize() { return udpBufferSize; }
    
    // === Setters (builder-style) ===
    
    public ServerConfig tcpPort(int port) { this.tcpPort = port; return this; }
    public ServerConfig udpPort(int port) { this.udpPort = port; return this; }
    public ServerConfig multicastPort(int port) { this.multicastPort = port; return this; }
    public ServerConfig tcpEnabled(boolean enabled) { this.tcpEnabled = enabled; return this; }
    public ServerConfig udpEnabled(boolean enabled) { this.udpEnabled = enabled; return this; }
    public ServerConfig multicastEnabled(boolean enabled) { this.multicastEnabled = enabled; return this; }
    public ServerConfig maxTcpClients(int max) { this.maxTcpClients = max; return this; }
    public ServerConfig engineChannelCapacity(int capacity) { this.engineChannelCapacity = capacity; return this; }
    public ServerConfig clientChannelCapacity(int capacity) { this.clientChannelCapacity = capacity; return this; }
    
    // === Convenience ===
    
    public String tcpAddress() {
        return tcpBindAddr + ":" + tcpPort;
    }
    
    public String udpAddress() {
        return udpBindAddr + ":" + udpPort;
    }
    
    public String multicastAddress() {
        return multicastGroup.getHostAddress() + ":" + multicastPort;
    }
    
    // === Helpers ===
    
    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }
    
    private static int getEnvInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                System.err.println("Invalid integer for " + key + ": " + value);
            }
        }
        return defaultValue;
    }
    
    private static boolean getEnvBool(String key, boolean defaultValue) {
        String value = System.getenv(key);
        if (value != null) {
            return value.equalsIgnoreCase("true") 
                || value.equals("1") 
                || value.equalsIgnoreCase("yes");
        }
        return defaultValue;
    }
    
    private static void printHelp() {
        System.err.println("engine-server - Multi-protocol matching engine server");
        System.err.println();
        System.err.println("OPTIONS:");
        System.err.println("  --tcp-port PORT    TCP port (default: 1234)");
        System.err.println("  --udp-port PORT    UDP port (default: 1235)");
        System.err.println("  --mcast-port PORT  Multicast port (default: 1236)");
        System.err.println("  --no-tcp           Disable TCP server");
        System.err.println("  --no-udp           Disable UDP server");
        System.err.println("  --no-mcast         Disable multicast publisher");
        System.err.println();
        System.err.println("ENVIRONMENT:");
        System.err.println("  ENGINE_TCP_PORT, ENGINE_UDP_PORT, ENGINE_MCAST_PORT");
        System.err.println("  ENGINE_TCP_ENABLED, ENGINE_UDP_ENABLED, ENGINE_MCAST_ENABLED");
    }
    
    @Override
    public String toString() {
        return String.format(
            "ServerConfig[tcp=%s:%d(%s), udp=%s:%d(%s), mcast=%s:%d(%s)]",
            tcpBindAddr, tcpPort, tcpEnabled ? "on" : "off",
            udpBindAddr, udpPort, udpEnabled ? "on" : "off",
            multicastGroup.getHostAddress(), multicastPort, multicastEnabled ? "on" : "off"
        );
    }
}
