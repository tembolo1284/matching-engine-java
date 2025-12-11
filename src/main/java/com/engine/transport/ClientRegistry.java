package com.engine.transport;

import com.engine.messages.OutputMessage;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public final class ClientRegistry {
    
    public record ClientEntry(ClientInfo info, BlockingQueue<OutputMessage> queue) {}
    
    private final ConcurrentHashMap<ClientId, ClientEntry> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ClientId> userToClient = new ConcurrentHashMap<>();
    private final int queueCapacity;
    
    public ClientRegistry(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }
    
    public BlockingQueue<OutputMessage> register(ClientInfo info) {
        BlockingQueue<OutputMessage> queue = new LinkedBlockingQueue<>(queueCapacity);
        clients.put(info.id(), new ClientEntry(info, queue));
        return queue;
    }
    
    public void unregister(ClientId clientId) {
        ClientEntry entry = clients.remove(clientId);
        if (entry != null && entry.info().userId() != null) {
            userToClient.remove(entry.info().userId());
        }
    }
    
    public void setUserId(ClientId clientId, int userId) {
        ClientEntry entry = clients.get(clientId);
        if (entry != null) {
            entry.info().setUserId(userId);
            userToClient.put(userId, clientId);
        }
    }
    
    public boolean sendToClient(ClientId clientId, OutputMessage message) {
        ClientEntry entry = clients.get(clientId);
        return entry != null && entry.queue().offer(message);
    }
    
    public int clientCount() {
        return clients.size();
    }
}
