package com.engine.transport;

import com.engine.messages.OutputMessage;
import com.engine.messages.Trade;
import com.engine.messages.TopOfBookUpdate;
import com.engine.protocol.BinaryCodec;
import com.engine.protocol.ProtocolException;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Multicast publisher for market data.
 * 
 * <p>Publishes trades and top-of-book updates to a multicast group
 * for market data distribution.
 * 
 * <h2>Packet Format</h2>
 * <pre>
 * [seq_num: 8 bytes BE] [frame_len: 4 bytes BE] [frame: N bytes]
 * </pre>
 */
public final class MulticastPublisher implements Runnable {
    
    private final ServerConfig config;
    private final BlockingQueue<OutputMessage> queue;
    private final Metrics metrics;
    
    private volatile boolean running = true;
    
    public MulticastPublisher(
            ServerConfig config,
            BlockingQueue<OutputMessage> queue,
            Metrics metrics) {
        
        this.config = config;
        this.queue = queue;
        this.metrics = metrics;
    }
    
    /**
     * Signal shutdown.
     */
    public void shutdown() {
        running = false;
    }
    
    @Override
    public void run() {
        try (DatagramSocket socket = new DatagramSocket()) {
            // Configure socket
            socket.setTrafficClass(0x10); // Low delay
            
            InetSocketAddress mcastAddr = new InetSocketAddress(
                config.multicastGroup(), 
                config.multicastPort()
            );
            
            System.err.println("Multicast publisher started on " + config.multicastAddress() 
                + " (TTL=" + config.multicastTtl() + ")");
            
            // Pre-allocate buffers
            ByteBuffer encodeBuffer = ByteBuffer.allocate(128).order(ByteOrder.BIG_ENDIAN);
            ByteBuffer packetBuffer = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
            long seqNum = 0;
            
            while (running) {
                OutputMessage msg = queue.poll(100, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    continue;
                }
                
                // Only publish market data (trades and TOB)
                if (!(msg instanceof Trade) && !(msg instanceof TopOfBookUpdate)) {
                    continue;
                }
                
                // Encode message
                encodeBuffer.clear();
                try {
                    BinaryCodec.INSTANCE.encodeOutput(msg, encodeBuffer);
                } catch (ProtocolException e) {
                    System.err.println("Multicast encode error: " + e.getMessage());
                    continue;
                }
                encodeBuffer.flip();
                int frameLen = encodeBuffer.remaining();
                
                // Build packet: [seq_num: 8] [frame_len: 4] [frame: N]
                packetBuffer.clear();
                packetBuffer.putLong(seqNum);
                packetBuffer.putInt(frameLen);
                packetBuffer.put(encodeBuffer);
                packetBuffer.flip();
                
                // Send
                byte[] packetBytes = new byte[packetBuffer.remaining()];
                packetBuffer.get(packetBytes);
                
                DatagramPacket packet = new DatagramPacket(
                    packetBytes, 
                    packetBytes.length,
                    mcastAddr
                );
                
                try {
                    socket.send(packet);
                    metrics.multicastMessages.increment();
                    seqNum++;
                } catch (IOException e) {
                    System.err.println("Multicast send error: " + e.getMessage());
                    metrics.sendErrors.increment();
                }
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SocketException e) {
            System.err.println("Multicast socket error: " + e.getMessage());
        }
        
        System.err.println("Multicast publisher stopped");
    }
}
