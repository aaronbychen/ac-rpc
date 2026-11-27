package com.ac.acrpc.server.tcp;

import com.ac.acrpc.RpcApplication;
import com.ac.acrpc.config.RpcConfig;
import com.ac.acrpc.exception.RpcException;
import com.ac.acrpc.model.RpcRequest;
import com.ac.acrpc.model.RpcResponse;
import com.ac.acrpc.model.ServiceMetaInfo;
import com.ac.acrpc.protocol.ProtocolConstant;
import com.ac.acrpc.protocol.ProtocolMessage;
import com.ac.acrpc.protocol.ProtocolMessageDecoder;
import com.ac.acrpc.protocol.ProtocolMessageEncoder;
import com.ac.acrpc.protocol.ProtocolMessageSerializerEnum;
import com.ac.acrpc.protocol.ProtocolMessageTypeEnum;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * TCP client for RPC calls.
 *
 * <p>One Vert.x instance is shared by all calls. Each provider endpoint gets a small pool of
 * long-lived connections, and requests are multiplexed over them: every request carries a
 * unique id, and the response with the same id completes the matching future. Calls have a
 * deadline, fail immediately when the endpoint is unreachable, and are rejected once a
 * connection already has too many requests in flight.
 */
@Slf4j
public final class VertxTcpClient {

    private static volatile VertxTcpClient shared;

    private final Vertx vertx;
    private final NetClient netClient;
    private final int connectionsPerEndpoint;
    private final int maxPendingPerConnection;
    private final ConcurrentMap<String, EndpointPool> pools = new ConcurrentHashMap<>();
    private final AtomicLong nextRequestId = new AtomicLong();

    public VertxTcpClient(int connectTimeoutMs, int connectionsPerEndpoint, int maxPendingPerConnection) {
        this.vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(
                Math.max(2, Math.min(connectionsPerEndpoint, Runtime.getRuntime().availableProcessors()))));
        this.netClient = vertx.createNetClient(new NetClientOptions()
                .setConnectTimeout(connectTimeoutMs)
                .setTcpNoDelay(true)
                .setTcpKeepAlive(true));
        this.connectionsPerEndpoint = connectionsPerEndpoint;
        this.maxPendingPerConnection = maxPendingPerConnection;
    }

    /**
     * Client configured from the global RPC config, created on first use.
     */
    public static VertxTcpClient shared() {
        if (shared == null) {
            synchronized (VertxTcpClient.class) {
                if (shared == null) {
                    RpcConfig config = RpcApplication.getRpcConfig();
                    shared = new VertxTcpClient(config.getConnectTimeoutMs(), config.getConnectionsPerEndpoint(),
                            config.getMaxPendingPerConnection());
                    Runtime.getRuntime().addShutdownHook(new Thread(shared::close));
                }
            }
        }
        return shared;
    }

    /**
     * Blocking call using the global config's serializer and deadline.
     */
    public static RpcResponse doRequest(RpcRequest rpcRequest, ServiceMetaInfo serviceMetaInfo)
            throws InterruptedException, ExecutionException {
        RpcConfig config = RpcApplication.getRpcConfig();
        return shared().request(rpcRequest, serviceMetaInfo, config.getSerializer(), config.getRequestTimeoutMs()).get();
    }

    /**
     * Sends a request and returns a future completed by its response, by an I/O error, or with
     * a {@link TimeoutException} once the deadline passes.
     */
    public CompletableFuture<RpcResponse> request(RpcRequest rpcRequest, ServiceMetaInfo target,
                                                  String serializer, long timeoutMs) {
        long requestId = nextRequestId.incrementAndGet();
        Buffer frame;
        try {
            frame = encode(rpcRequest, requestId, serializer);
        } catch (IOException | RuntimeException e) {
            return CompletableFuture.failedFuture(new RpcException("Failed to encode request: " + e.getMessage()));
        }
        CompletableFuture<RpcResponse> response = new CompletableFuture<>();
        String key = target.getServiceHost() + ":" + target.getServicePort();
        pools.computeIfAbsent(key, k -> new EndpointPool(target.getServiceHost(), target.getServicePort()))
                .acquire()
                .whenComplete((connection, error) -> {
                    if (error != null) {
                        response.completeExceptionally(new RpcException("Cannot connect to " + key + ": " + error.getMessage()));
                    } else {
                        connection.send(requestId, frame, response);
                    }
                });
        return response.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void close() {
        pools.values().forEach(EndpointPool::closeAll);
        vertx.close();
    }

    private static Buffer encode(RpcRequest rpcRequest, long requestId, String serializer) throws IOException {
        ProtocolMessageSerializerEnum serializerEnum = ProtocolMessageSerializerEnum.getEnumByValue(serializer);
        if (serializerEnum == null) {
            throw new RpcException("Unknown serializer: " + serializer);
        }
        ProtocolMessage.Header header = new ProtocolMessage.Header();
        header.setMagic(ProtocolConstant.PROTOCOL_MAGIC);
        header.setVersion(ProtocolConstant.PROTOCOL_VERSION);
        header.setSerializer((byte) serializerEnum.getKey());
        header.setType((byte) ProtocolMessageTypeEnum.REQUEST.getKey());
        header.setRequestId(requestId);
        return ProtocolMessageEncoder.encode(new ProtocolMessage<>(header, rpcRequest));
    }

    /**
     * Fixed number of connection slots for one endpoint, used round-robin. A slot whose
     * connection is closed or failed to connect is reconnected on the next use.
     */
    private final class EndpointPool {

        private final String host;
        private final int port;
        private final AtomicReferenceArray<CompletableFuture<Connection>> slots;
        private final AtomicInteger next = new AtomicInteger();

        EndpointPool(String host, int port) {
            this.host = host;
            this.port = port;
            this.slots = new AtomicReferenceArray<>(connectionsPerEndpoint);
        }

        CompletableFuture<Connection> acquire() {
            int index = Math.floorMod(next.getAndIncrement(), slots.length());
            while (true) {
                CompletableFuture<Connection> current = slots.get(index);
                if (current != null && isUsable(current)) {
                    return current;
                }
                CompletableFuture<Connection> fresh = new CompletableFuture<>();
                if (slots.compareAndSet(index, current, fresh)) {
                    connect(fresh);
                    return fresh;
                }
            }
        }

        private boolean isUsable(CompletableFuture<Connection> slot) {
            if (!slot.isDone()) {
                return true; // connect in progress; share it
            }
            if (slot.isCompletedExceptionally()) {
                return false;
            }
            return !slot.join().closed;
        }

        private void connect(CompletableFuture<Connection> slot) {
            netClient.connect(port, host).onComplete(ar -> {
                if (ar.succeeded()) {
                    slot.complete(new Connection(ar.result()));
                } else {
                    slot.completeExceptionally(ar.cause());
                }
            });
        }

        void closeAll() {
            for (int i = 0; i < slots.length(); i++) {
                CompletableFuture<Connection> slot = slots.get(i);
                if (slot != null && slot.isDone() && !slot.isCompletedExceptionally()) {
                    slot.join().socket.close();
                }
            }
        }
    }

    /**
     * One TCP connection and the requests currently awaiting a response on it.
     */
    private final class Connection {

        private final NetSocket socket;
        private final ConcurrentMap<Long, CompletableFuture<RpcResponse>> pending = new ConcurrentHashMap<>();
        private volatile boolean closed;

        Connection(NetSocket socket) {
            this.socket = socket;
            socket.handler(new TcpBufferHandlerWrapper(this::onFrame));
            socket.closeHandler(v -> failAll(new RpcException("Connection closed")));
            socket.exceptionHandler(e -> {
                log.warn("Connection error on {}: {}", socket.remoteAddress(), e.toString());
                socket.close();
            });
        }

        void send(long requestId, Buffer frame, CompletableFuture<RpcResponse> response) {
            if (closed) {
                response.completeExceptionally(new RpcException("Connection closed"));
                return;
            }
            if (pending.size() >= maxPendingPerConnection) {
                response.completeExceptionally(new RpcException("Too many in-flight requests on connection"));
                return;
            }
            pending.put(requestId, response);
            // Drop the entry however the call ends (response, timeout, or connection failure)
            response.whenComplete((r, e) -> pending.remove(requestId));
            socket.write(frame).onFailure(e -> response.completeExceptionally(
                    new RpcException("Write failed: " + e.getMessage())));
        }

        @SuppressWarnings("unchecked")
        private void onFrame(Buffer buffer) {
            ProtocolMessage<RpcResponse> message;
            try {
                message = (ProtocolMessage<RpcResponse>) ProtocolMessageDecoder.decode(buffer);
            } catch (IOException | RuntimeException e) {
                log.warn("Dropping undecodable frame from {}: {}", socket.remoteAddress(), e.toString());
                return;
            }
            CompletableFuture<RpcResponse> response = pending.remove(message.getHeader().getRequestId());
            if (response != null) {
                response.complete(message.getBody());
            }
            // else: the caller already timed out
        }

        private void failAll(RpcException cause) {
            closed = true;
            pending.values().forEach(f -> f.completeExceptionally(cause));
        }
    }
}
