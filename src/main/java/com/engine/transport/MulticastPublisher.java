package com.engine.transport;

import com.engine.messages.OutputMessage;
import com.engine.messages.Trade;
import com.engine.messages.TopOfBookUpdate;
import com.engine.protocol.BinaryCodec;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class MulticastPublisher implements Runnable {
    
    private final BlockingQueue<OutputMessage> queue;
    private final String group;
    private final int port;
    private final Metrics metrics;
    private volatile boolean running = true;
    private long seqNum = 0;
    
    public MulticastPublisher(BlockingQueue<OutputMessage> queue, String group, int port, Metrics metrics) {
        this.queue = queue;
        this.group = group;
        this.port = port;
        this.metrics = metrics;
    }
    
    public void shutdown() {
        running = false;
    }
    
    @Override
    public void run() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setTrafficClass(0x10); // low delay
            InetAddress groupAddr = InetAddress.getByName(group);
            
            ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
            
            System.err.println("Multicast publisher: " + group + ":" + port);
            
            while (running) {
                OutputMessage msg = queue.poll(100, TimeUnit.MILLISECONDS);
                if (msg == null) continue;
                
                // Only publish trades and TOB updates
                if (!(msg instanceof Trade) && !(msg instanceof TopOfBookUpdate)) continue;
                
                buf.clear();
                buf.putLong(++seqNum);
                buf.putInt(0); // placeholder for frame length
                int start = buf.position();
                BinaryCodec.INSTANCE.encodeOutput(msg, buf);
                int frameLen = buf.position() - start;
                buf.putInt(8, frameLen);
                
                DatagramPacket packet = new DatagramPacket(buf.array(), buf.position(), groupAddr, port);
                socket.send(packet);
                metrics.messagesSent.increment();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Multicast error: " + e.getMessage());
        }
        System.err.println("Multicast publisher: stopped");
    }
}
