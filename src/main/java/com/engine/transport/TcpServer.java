package com.engine.transport;

import com.engine.messages.InputMessage;
import com.engine.messages.OutputMessage;
import com.engine.protocol.*;
import com.engine.transport.ClientInfo.Transport;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.*;

/**
 * TCP server supporting CSV, Binary, and FIX protocols.
 * 
 * <p>Architecture:
 * <ul>
 *   <li>Main thread accepts connections</li>
 *   <li>Each client gets a reader thread and writer thread</li>
 *   <li>Protocol auto-detected from first bytes</li>
 *   <li>Cached thread pool for efficient concurrency</li>
 * </ul>
 */
public final class TcpServer {
    
    private final ServerConfig config;
    private final ClientRegistry registry;
    private final BlockingQueue<EngineRequest> engineQueue;
    private final Metrics metrics;
    private final ExecutorService executor;
    
    private volatile boolean running = true;
    private ServerSocket serverSocket;
    
    public TcpServer(
            ServerConfig config,
            ClientRegistry registry,
            BlockingQueue<EngineRequest> engineQueue,
            Metrics metrics) {
        
        this.config = config;
        this.registry = registry;
        this.engineQueue = engineQueue;
        this.metrics = metrics;
        
        // Use cached thread pool for scalable I/O (Java 17 compatible)
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Start the TCP server (blocks until shutdown).
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.tcpBindAddr(), config.tcpPort()));
        
        System.err.println("TCP server listening on " + config.tcpAddress());
        
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                
                // Check client limit
                if (registry.clientCount() >= config.maxTcpClients()) {
                    System.err.println("Rejecting " + clientSocket.getRemoteSocketAddress() 
                        + ": max clients reached");
                    clientSocket.close();
                    continue;
                }
                
                // Handle client in thread pool
                executor.submit(() -> handleClient(clientSocket));
                
            } catch (SocketException e) {
                if (running) {
                    System.err.println("Accept error: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Stop the server.
     */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
        executor.shutdown();
    }
    
    /**
     * Handle a single TCP client connection.
     */
    private void handleClient(Socket socket) {
        ClientId clientId = ClientId.next();
        SocketAddress peerAddr = socket.getRemoteSocketAddress();
        
        metrics.tcpConnectionsTotal.increment();
        metrics.tcpConnectionsActive.increment();
        
        try {
            // Configure socket
            socket.setTcpNoDelay(true);
            socket.setSoTimeout((int) config.readTimeout().toMillis());
            
            InputStream in = new BufferedInputStream(socket.getInputStream(), config.tcpReadBufferSize());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            
            // Detect protocol from first bytes
            in.mark(8);
            byte[] peek = new byte[8];
            int peekLen = in.read(peek);
            in.reset();
            
            Codec.Type protocolType = detectProtocol(peek, peekLen);
            Codec codec = getCodec(protocolType);
            
            System.err.println(clientId + ": connected from " + peerAddr + " using " + protocolType);
            
            // Register client
            ClientInfo info = new ClientInfo(clientId, peerAddr, Transport.TCP, protocolType);
            BlockingQueue<OutputMessage> outboundQueue = registry.register(info);
            
            // Start writer thread
            Thread writerThread = new Thread(() -> 
                runWriter(clientId, out, outboundQueue, codec));
            writerThread.setDaemon(true);
            writerThread.start();
            
            // Run reader loop (in current thread)
            runReader(clientId, in, codec);
            
            // Cleanup
            writerThread.interrupt();
            
        } catch (IOException e) {
            System.err.println(clientId + ": error: " + e.getMessage());
        } finally {
            registry.unregister(clientId);
            metrics.tcpConnectionsActive.decrement();
            
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
            
            System.err.println(clientId + ": disconnected");
        }
    }
    
    /**
     * Reader loop - decode messages and submit to engine.
     */
    private void runReader(ClientId clientId, InputStream in, Codec codec) {
        try {
            if (codec.type() == Codec.Type.CSV) {
                runCsvReader(clientId, in);
            } else {
                runBinaryReader(clientId, in);
            }
        } catch (SocketTimeoutException e) {
            System.err.println(clientId + ": read timeout");
        } catch (IOException e) {
            if (running) {
                System.err.println(clientId + ": read error: " + e.getMessage());
            }
        }
    }
    
    /**
     * CSV reader loop.
     */
    private void runCsvReader(ClientId clientId, InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String line;
        
        while (running && (line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            
            try {
                var msgOpt = CsvCodec.INSTANCE.decodeInputLine(trimmed);
                if (msgOpt.isPresent()) {
                    submitRequest(clientId, msgOpt.get());
                }
            } catch (ProtocolException e) {
                metrics.decodeErrors.increment();
                System.err.println(clientId + ": invalid CSV: " + trimmed);
            }
        }
    }
    
    /**
     * Binary reader loop (length-prefixed framing).
     */
    private void runBinaryReader(ClientId clientId, InputStream in) throws IOException {
        DataInputStream dataIn = new DataInputStream(in);
        byte[] frameBuf = new byte[256];
        ByteBuffer buffer = ByteBuffer.wrap(frameBuf).order(ByteOrder.BIG_ENDIAN);
        
        while (running) {
            // Read 4-byte length prefix
            int frameLen;
            try {
                frameLen = dataIn.readInt();
            } catch (EOFException e) {
                break; // Client disconnected
            }
            
            // Validate frame length
            if (frameLen <= 0 || frameLen > 65536) {
                System.err.println(clientId + ": invalid frame length: " + frameLen);
                break;
            }
            
            // Resize buffer if needed
            if (frameBuf.length < frameLen) {
                frameBuf = new byte[frameLen];
                buffer = ByteBuffer.wrap(frameBuf).order(ByteOrder.BIG_ENDIAN);
            }
            
            // Read frame payload
            dataIn.readFully(frameBuf, 0, frameLen);
            
            // Decode message
            buffer.position(0).limit(frameLen);
            try {
                var msgOpt = BinaryCodec.INSTANCE.decodeInput(buffer);
                if (msgOpt.isPresent()) {
                    submitRequest(clientId, msgOpt.get());
                }
            } catch (ProtocolException e) {
                metrics.decodeErrors.increment();
                System.err.println(clientId + ": decode error: " + e.getMessage());
            }
        }
    }
    
    /**
     * Submit a request to the engine queue.
     */
    private void submitRequest(ClientId clientId, InputMessage msg) {
        EngineRequest request = EngineRequest.of(clientId, msg);
        
        if (!engineQueue.offer(request)) {
            metrics.channelFullDrops.increment();
            System.err.println(clientId + ": engine queue full, dropping message");
        }
    }
    
    /**
     * Writer loop - send outbound messages to client.
     */
    private void runWriter(
            ClientId clientId,
            OutputStream out,
            BlockingQueue<OutputMessage> queue,
            Codec codec) {
        
        ByteBuffer buffer = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
        
        try {
            while (running) {
                OutputMessage msg = queue.poll(100, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    continue;
                }
                
                if (codec.type() == Codec.Type.CSV) {
                    String line = CsvCodec.INSTANCE.encodeOutputLine(msg) + "\n";
                    out.write(line.getBytes());
                } else {
                    // Length-prefixed binary
                    buffer.clear();
                    BinaryCodec.INSTANCE.encodeOutput(msg, buffer);
                    buffer.flip();
                    
                    int frameLen = buffer.remaining();
                    out.write((frameLen >> 24) & 0xFF);
                    out.write((frameLen >> 16) & 0xFF);
                    out.write((frameLen >> 8) & 0xFF);
                    out.write(frameLen & 0xFF);
                    out.write(buffer.array(), 0, frameLen);
                }
                
                out.flush();
                metrics.messagesSent.increment();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running) {
                metrics.sendErrors.increment();
            }
        }
    }
    
    /**
     * Detect protocol from first bytes.
     */
    private Codec.Type detectProtocol(byte[] peek, int len) {
        if (len <= 0) {
            return Codec.Type.CSV;
        }
        
        ByteBuffer buf = ByteBuffer.wrap(peek, 0, len);
        var protocol = ProtocolDetector.detect(buf);
        
        return switch (protocol) {
            case BINARY -> Codec.Type.BINARY;
            case FIX -> Codec.Type.CSV; // FIX not implemented, fallback to CSV
            default -> Codec.Type.CSV;
        };
    }
    
    /**
     * Get codec for protocol type.
     */
    private Codec getCodec(Codec.Type type) {
        return switch (type) {
            case BINARY -> BinaryCodec.INSTANCE;
            default -> CsvCodec.INSTANCE;
        };
    }
}
