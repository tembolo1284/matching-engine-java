package com.engine.transport;

import com.engine.protocol.Codec;
import java.net.SocketAddress;

public final class ClientInfo {
    
    public enum Transport { TCP, UDP }
    
    private final ClientId id;
    private final SocketAddress address;
    private final Transport transport;
    private final Codec.Type protocol;
    private volatile Integer userId;
    
    public ClientInfo(ClientId id, SocketAddress address, Transport transport, Codec.Type protocol) {
        this.id = id;
        this.address = address;
        this.transport = transport;
        this.protocol = protocol;
    }
    
    public ClientId id() { return id; }
    public SocketAddress address() { return address; }
    public Transport transport() { return transport; }
    public Codec.Type protocol() { return protocol; }
    public Integer userId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }
}
