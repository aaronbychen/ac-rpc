package com.ac.acrpc.server.tcp;

import com.ac.acrpc.exception.RpcException;
import com.ac.acrpc.model.RpcRequest;
import com.ac.acrpc.model.RpcResponse;
import com.ac.acrpc.model.ServiceMetaInfo;
import com.ac.acrpc.protocol.ProtocolConstant;
import com.ac.acrpc.protocol.ProtocolMessage;
import com.ac.acrpc.protocol.ProtocolMessageDecoder;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Blocking-socket client designed for virtual threads.
 *
 * <p>The calling thread writes its request frame and waits for the response; one virtual
 * thread per connection reads response frames and completes the waiting call by request id.
 * Pooling, multiplexing, deadlines and the in-flight cap match {@link VertxTcpClient}.
 *
 * <p>The write lock can be a {@code synchronized} monitor or a {@link ReentrantLock}. On
 * JDK 21, a virtual thread that blocks while holding or waiting for a monitor pins its carrier
 * thread; JDK 24 removed that restriction (JEP 491). The option exists to measure the effect.
 */
@Slf4j
public final class BlockingTcpClient implements RpcClient {

    public enum WriteLock { SYNCHRONIZED, REENTRANT }

    private final int connectTimeoutMs;
    private final int connectionsPerEndpoint;
    private final int maxPendingPerConnection;
    private final WriteLock writeLock;
    private final ConcurrentMap<String, Endpoint> endpoints = new ConcurrentHashMap<>();
    private final AtomicLong nextRequestId = new AtomicLong();
    private volatile boolean closed;

    public BlockingTcpClient(int connectTimeoutMs, int connectionsPerEndpoint, int maxPendingPerConnection,
                             WriteLock writeLock) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.connectionsPerEndpoint = connectionsPerEndpoint;
        this.maxPendingPerConnection = maxPendingPerConnection;
        this.writeLock = writeLock;
    }

    @Override
    public RpcResponse call(RpcRequest request, ServiceMetaInfo target, String serializer, long timeoutMs)
            throws Exception {
        long requestId = nextRequestId.incrementAndGet();
        byte[] frame = Frames.encodeRequest(request, requestId, serializer).getBytes();
        String key = target.getServiceHost() + ":" + target.getServicePort();
        Connection connection;
        try {
            connection = endpoints.computeIfAbsent(key, k -> new Endpoint(target.getServiceHost(), target.getServicePort()))
                    .acquire();
        } catch (IOException e) {
            throw new RpcException("Cannot connect to " + key + ": " + e.getMessage());
        }
        return connection.call(requestId, frame, timeoutMs);
    }

    @Override
    public void close() {
        closed = true;
        endpoints.values().forEach(Endpoint::closeAll);
    }

    /**
     * Fixed connection slots for one endpoint, used round-robin and reconnected when closed.
     */
    private final class Endpoint {

        private final String host;
        private final int port;
        private final Connection[] slots = new Connection[connectionsPerEndpoint];
        private final ReentrantLock[] slotLocks = new ReentrantLock[connectionsPerEndpoint];
        private final AtomicInteger next = new AtomicInteger();

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
            for (int i = 0; i < slotLocks.length; i++) {
                slotLocks[i] = new ReentrantLock();
            }
        }

        Connection acquire() throws IOException {
            int index = Math.floorMod(next.getAndIncrement(), slots.length);
            Connection current = slots[index];
            if (current != null && !current.closed) {
                return current;
            }
            ReentrantLock lock = slotLocks[index];
            lock.lock();
            try {
                current = slots[index];
                if (current == null || current.closed) {
                    if (closed) {
                        throw new IOException("client closed");
                    }
                    current = new Connection(host, port);
                    slots[index] = current;
                }
                return current;
            } finally {
                lock.unlock();
            }
        }

        void closeAll() {
            for (Connection c : slots) {
                if (c != null) {
                    c.close(new RpcException("Client closed"));
                }
            }
        }
    }

    /**
     * One socket, its reader thread, and the calls awaiting responses on it.
     */
    private final class Connection {

        private final Socket socket;
        private final OutputStream out;
        private final Object monitor = new Object();
        private final ReentrantLock lock = new ReentrantLock();
        private final ConcurrentMap<Long, CompletableFuture<RpcResponse>> pending = new ConcurrentHashMap<>();
        private volatile boolean closed;

        Connection(String host, int port) throws IOException {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            out = socket.getOutputStream();
            Thread.ofVirtual().name("acrpc-reader-" + socket.getLocalPort()).start(this::readLoop);
        }

        RpcResponse call(long requestId, byte[] frame, long timeoutMs) throws Exception {
            if (closed) {
                throw new RpcException("Connection closed");
            }
            if (pending.size() >= maxPendingPerConnection) {
                throw new RpcException("Too many in-flight requests on connection");
            }
            CompletableFuture<RpcResponse> response = new CompletableFuture<>();
            pending.put(requestId, response);
            try {
                write(frame);
                return response.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            } catch (IOException e) {
                close(new RpcException("Write failed: " + e.getMessage()));
                throw new RpcException("Write failed: " + e.getMessage());
            } finally {
                pending.remove(requestId);
            }
        }

        private void write(byte[] frame) throws IOException {
            if (writeLock == WriteLock.SYNCHRONIZED) {
                synchronized (monitor) {
                    out.write(frame);
                }
            } else {
                lock.lock();
                try {
                    out.write(frame);
                } finally {
                    lock.unlock();
                }
            }
        }

        private void readLoop() {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024))) {
                byte[] header = new byte[ProtocolConstant.MESSAGE_HEADER_LENGTH];
                while (!closed) {
                    in.readFully(header);
                    Buffer frame = Buffer.buffer(header);
                    byte[] body = new byte[frame.getInt(13)];
                    in.readFully(body);
                    frame.appendBytes(body);
                    dispatch(frame);
                }
            } catch (IOException e) {
                close(new RpcException("Connection closed: " + e.getMessage()));
            }
        }

        @SuppressWarnings("unchecked")
        private void dispatch(Buffer frame) {
            ProtocolMessage<RpcResponse> message;
            try {
                message = (ProtocolMessage<RpcResponse>) ProtocolMessageDecoder.decode(frame);
            } catch (IOException | RuntimeException e) {
                log.warn("Dropping undecodable frame: {}", e.toString());
                return;
            }
            CompletableFuture<RpcResponse> response = pending.remove(message.getHeader().getRequestId());
            if (response != null) {
                response.complete(message.getBody());
            }
        }

        void close(RpcException cause) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // already failing
            }
            pending.values().forEach(f -> f.completeExceptionally(cause));
        }
    }
}
