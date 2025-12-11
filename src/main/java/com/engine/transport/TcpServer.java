package com.engine.transport;

import com.engine.messages.InputMessage;
import com.engine.messages.OutputMessage;
import com.engine.protocol.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.*;

public final class TcpServer implements Runnable {
    
    private final int port;
    private final int maxClients;
    private final BlockingQueue<EngineRequest> engineQueue;
    private final ClientRegistry registry;
    private final Metrics metrics;
    private final ExecutorService executor;
    private volatile boolean running = true;
    private ServerSocket serverSocket;
    
    public TcpServer(int port, int maxClients, BlockingQueue<EngineRequest> engineQueue,
                     ClientRegistry registry, Metrics metrics) {
        this.port = port;
        this.maxClients = maxClients;
        this.engineQueue = engineQueue;
        this.registry = registry;
        this.metrics = metrics;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }
    
    public void shutdown() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        executor.shutdownNow();
    }
    
    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            System.err.println("TCP server: listening on port " + port);
            
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    if (registry.clientCount() >= maxClients) {
                        socket.close();
                        continue;
                    }
                    executor.submit(() -> handleClient(socket));
                } catch (IOException e) {
                    if (running) System.err.println("Accept error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("TCP server error: " + e.getMessage());
        }
        System.err.println("TCP server: stopped");
    }
    
    private void handleClient(Socket socket) {
        ClientId clientId = ClientId.next();
        Codec.Type protocol = Codec.Type.CSV;
        
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30000);
            
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            
            // Detect protocol from first byte
            int first = in.read();
            if (first < 0) return;
            
            protocol = (first == WireConstants.MAGIC) ? Codec.Type.BINARY : Codec.Type.CSV;
            
            ClientInfo info = new ClientInfo(clientId, socket.getRemoteSocketAddress(),
                                            ClientInfo.Transport.TCP, protocol);
            BlockingQueue<OutputMessage> outQueue = registry.register(info);
            
            System.err.println(clientId + ": connected (" + protocol + ")");
            
            // Start writer thread
            executor.submit(() -> runWriter(clientId, outQueue, out, protocol));
            
            // Reader loop
            if (protocol == Codec.Type.BINARY) {
                runBinaryReader(clientId, in, (byte) first);
            } else {
                runCsvReader(clientId, in, (byte) first);
            }
            
        } catch (Exception e) {
            if (running) System.err.println(clientId + ": " + e.getMessage());
        } finally {
            registry.unregister(clientId);
            try { socket.close(); } catch (IOException ignored) {}
            System.err.println(clientId + ": disconnected");
        }
    }
    
    private void runCsvReader(ClientId clientId, InputStream in, byte firstByte) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        sb.append((char) firstByte);
        
        String line;
        // Read rest of first line
        String rest = reader.readLine();
        if (rest != null) {
            sb.append(rest);
            processLine(clientId, sb.toString());
        }
        
        // Read subsequent lines
        while (running && (line = reader.readLine()) != null) {
            processLine(clientId, line);
        }
    }
    
    private void processLine(ClientId clientId, String line) {
        try {
            var msgOpt = CsvCodec.INSTANCE.decodeInputLine(line);
            if (msgOpt.isPresent()) {
                submitRequest(clientId, msgOpt.get());
            }
        } catch (ProtocolException e) {
            metrics.decodeErrors.increment();
            System.err.println(clientId + ": decode error: " + e.getMessage());
        }
    }
    
    private void runBinaryReader(ClientId clientId, InputStream in, byte firstByte) throws IOException {
        ByteBuffer lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        byte[] frameBuf = new byte[1024];
        ByteBuffer frame = ByteBuffer.wrap(frameBuf).order(ByteOrder.BIG_ENDIAN);
        
        // Handle first message (already read magic byte)
        lenBuf.put(firstByte);
        if (in.readNBytes(lenBuf.array(), 1, 3) < 3) return;
        int len = lenBuf.getInt(0) - 4;
        if (len > 0 && len < frameBuf.length) {
            if (in.readNBytes(frameBuf, 0, len) == len) {
                frame.position(0).limit(len);
                processFrame(clientId, frame);
            }
        }
        
        // Read subsequent frames
        while (running) {
            lenBuf.clear();
            if (in.readNBytes(lenBuf.array(), 0, 4) < 4) break;
            len = lenBuf.getInt(0);
            if (len <= 0 || len > frameBuf.length) break;
            if (in.readNBytes(frameBuf, 0, len) < len) break;
            frame.position(0).limit(len);
            processFrame(clientId, frame);
        }
    }
    
    private void processFrame(ClientId clientId, ByteBuffer frame) {
        try {
            var msgOpt = BinaryCodec.INSTANCE.decodeInput(frame);
            if (msgOpt.isPresent()) {
                submitRequest(clientId, msgOpt.get());
            }
        } catch (ProtocolException e) {
            metrics.decodeErrors.increment();
            System.err.println(clientId + ": decode error: " + e.getMessage());
        }
    }
    
    private void submitRequest(ClientId clientId, InputMessage msg) {
        if (!engineQueue.offer(EngineRequest.of(clientId, msg))) {
            metrics.channelFullDrops.increment();
        }
    }
    
    private void runWriter(ClientId clientId, BlockingQueue<OutputMessage> queue,
                          OutputStream out, Codec.Type protocol) {
        ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
        
        try {
            while (running) {
                OutputMessage msg = queue.poll(100, TimeUnit.MILLISECONDS);
                if (msg == null) continue;
                
                buf.clear();
                if (protocol == Codec.Type.BINARY) {
                    buf.position(4); // reserve for length
                    BinaryCodec.INSTANCE.encodeOutput(msg, buf);
                    int len = buf.position() - 4;
                    buf.putInt(0, len);
                    out.write(buf.array(), 0, buf.position());
                } else {
                    String line = CsvCodec.INSTANCE.encodeOutputLine(msg) + "\n";
                    out.write(line.getBytes());
                }
                out.flush();
                metrics.messagesSent.increment();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running) metrics.sendErrors.increment();
        }
    }
}
