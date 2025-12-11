package com.engine.transport;

import com.engine.messages.OutputMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Thread-safe registry of connected clients.
 * 
 * <p>Manages:
 * <ul>
 *   <li>Client ID → Client entry mapping</li>
 *   <li>User ID → Client ID mapping (for routing trades)</li>
 *   <li>Per-client outbound message queues</li>
 * </ul>
 */
public final class ClientRegistry {
    
    /**
     * Entry for a registered client.
     */
    public record ClientEntry(
        ClientInfo info,
        BlockingQueue<OutputMessage> outboundQueue
    ) {}
    
    /** ClientId → ClientEntry */
    private final ConcurrentHashMap<ClientId, ClientEntry> clients = new ConcurrentHashMap<>();
    
    /** UserId → ClientId for routing responses */
    private final ConcurrentHashMap<Integer, ClientId> userToClient = new ConcurrentHashMap<>();
    
    /** Capacity for per-client outbound queues */
    private final int clientQueueCapacity;
    
    public ClientRegistry(int clientQueueCapacity) {
        this.clientQueueCapacity = clientQueueCapacity;
    }
    
    /**
     * Register a new client.
     * 
     * @param info client information
     * @return the outbound queue for this client (writer thread should drain this)
     */
    public BlockingQueue<OutputMessage> register(ClientInfo info) {
        BlockingQueue<OutputMessage> queue = new LinkedBlockingQueue<>(clientQueueCapacity);
        ClientEntry entry = new ClientEntry(info, queue);
        clients.put(info.id(), entry);
        return queue;
    }
    
    /**
     * Unregister a client.
     */
    public void unregister(ClientId clientId) {
        ClientEntry entry = clients.remove(clientId);
        if (entry != null && entry.info().userId() != null) {
            userToClient.remove(entry.info().userId());
        }
    }
    
    /**
     * Associate a user ID with a client.
     * 
     * <p>Called when we see the first order from a client to track
     * the user ID for routing trade responses.
     */
    public void setUserId(ClientId clientId, int userId) {
        ClientEntry entry = clients.get(clientId);
        if (entry != null) {
            entry.info().setUserId(userId);
            userToClient.put(userId, clientId);
        }
    }
    
    /**
     * Get the client ID for a user ID.
     */
    public ClientId getClientForUser(int userId) {
        return userToClient.get(userId);
    }
    
    /**
     * Send a message to a specific client.
     * 
     * @return true if message was queued, false if client not found or queue full
     */
    public boolean sendToClient(ClientId clientId, OutputMessage msg) {
        ClientEntry entry = clients.get(clientId);
        if (entry != null) {
            return entry.outboundQueue().offer(msg);
        }
        return false;
    }
    
    /**
     * Send a message to a user (by user ID).
     * 
     * @return true if message was queued
     */
    public boolean sendToUser(int userId, OutputMessage msg) {
        ClientId clientId = userToClient.get(userId);
        if (clientId != null) {
            return sendToClient(clientId, msg);
        }
        return false;
    }
    
    /**
     * Broadcast a message to all clients.
     * 
     * <p>Best-effort: drops message for clients with full queues.
     */
    public void broadcast(OutputMessage msg) {
        for (ClientEntry entry : clients.values()) {
            entry.outboundQueue().offer(msg);
        }
    }
    
    /**
     * Get current client count.
     */
    public int clientCount() {
        return clients.size();
    }
    
    /**
     * Get all client IDs.
     */
    public List<ClientId> clientIds() {
        return new ArrayList<>(clients.keySet());
    }
    
    /**
     * Check if a client is registered.
     */
    public boolean contains(ClientId clientId) {
        return clients.containsKey(clientId);
    }
    
    /**
     * Get client info.
     */
    public ClientInfo getClientInfo(ClientId clientId) {
        ClientEntry entry = clients.get(clientId);
        return entry != null ? entry.info() : null;
    }
}
