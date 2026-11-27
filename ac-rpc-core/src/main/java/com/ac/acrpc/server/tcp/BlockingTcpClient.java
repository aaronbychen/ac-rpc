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
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Blocking-socket client designed for virtual threads.
 *
 * <p>Each connection has a writer thread and a reader thread. The writer drains an outbox of request frames and
 * writes everything queued in one flush, and a reader completes waiting calls by request id.
 * Callers only enqueue and wait, so the deadline covers the whole call, including time spent
 * queued, and a stalled socket cannot block callers past their deadline. Pooling, multiplexing
 * and the in-flight cap match {@link VertxTcpClient}.
 *
 * <p>An earlier version (tag {@code blocking-v1}) had each caller write its own frame under a
 * lock. Under load nearly every in-flight call was queued on that lock, throughput topped out
 * well below the Vert.x client, and since the deadline only started after the write, a stalled
 * write could hold every caller indefinitely.
 */
@Slf4j
public final class BlockingTcpClient implements RpcClient {

    private static final int MAX_BATCH = 256;

    private final int connectTimeoutMs;
    private final int connectionsPerEndpoint;
    private final int maxPendingPerConnection;
    private final ConcurrentMap<String, Endpoint> endpoints = new ConcurrentHashMap<>();
    private final AtomicLong nextRequestId = new AtomicLong();
    private volatile boolean closed;

    public BlockingTcpClient(int connectTimeoutMs, int connectionsPerEndpoint, int maxPendingPerConnection) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.connectionsPerEndpoint = connectionsPerEndpoint;
        this.maxPendingPerConnection = maxPendingPerConnection;
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
     * One socket, its writer and reader threads, and the calls awaiting responses on it.
     */
    private final class Connection {

        private final Socket socket;
        private final LinkedBlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();
        private final ConcurrentMap<Long, CompletableFuture<RpcResponse>> pending = new ConcurrentHashMap<>();
        private final Thread writer;
        private volatile boolean closed;

        Connection(String host, int port) throws IOException {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
            // The writer is a platform thread so that only one direction of the socket parks in the
            // JDK's virtual-thread poller. On Windows, a virtual thread blocked reading and another
            // blocked writing on the same socket can miss their wakeup (JDK-8334574); see
            // bench/repro/PollerStall.java. One platform thread per connection is cheap.
            writer = Thread.ofPlatform().daemon().name("acrpc-writer-" + socket.getLocalPort())
                    .start(() -> writeLoop(out));
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
                outbox.add(frame);
                if (closed) {
                    // Closed between the check above and registration; failAll may have missed us
                    throw new RpcException("Connection closed");
                }
                return response.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            } finally {
                pending.remove(requestId);
            }
        }

        /**
         * Writes every queued frame with a single flush, so syscalls scale with batches, not calls.
         */
        private void writeLoop(OutputStream out) {
            List<byte[]> batch = new ArrayList<>(MAX_BATCH);
            try {
                while (!closed) {
                    batch.add(outbox.take());
                    outbox.drainTo(batch, MAX_BATCH - 1);
                    for (byte[] frame : batch) {
                        out.write(frame);
                    }
                    out.flush();
                    batch.clear();
                }
            } catch (InterruptedException e) {
                // closing
            } catch (IOException e) {
                close(new RpcException("Write failed: " + e.getMessage()));
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
            writer.interrupt();
            outbox.clear();
            try {
                socket.close();
            } catch (IOException ignored) {
                // already failing
            }
            pending.values().forEach(f -> f.completeExceptionally(cause));
        }
    }
}
