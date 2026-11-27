package com.ac.acrpc.bench;

import com.ac.acrpc.bench.grpc.EchoGrpc;
import com.ac.acrpc.bench.grpc.EchoMessage;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * gRPC baseline client: {@code connections} plaintext channels (one HTTP/2 connection each),
 * used round-robin, mirroring ac-rpc's per-endpoint connection pool.
 */
final class GrpcEchoClient implements EchoClient {

    private final EchoGrpc.EchoBlockingStub[] stubs;
    private final AtomicInteger next = new AtomicInteger();
    private final long deadlineMs;

    GrpcEchoClient(String host, int port, int connections, long deadlineMs) {
        this.deadlineMs = deadlineMs;
        this.stubs = new EchoGrpc.EchoBlockingStub[connections];
        for (int i = 0; i < connections; i++) {
            ManagedChannel channel = NettyChannelBuilder.forAddress(host, port).usePlaintext().build();
            stubs[i] = EchoGrpc.newBlockingStub(channel);
        }
    }

    @Override
    public byte[] echo(byte[] payload) {
        EchoGrpc.EchoBlockingStub stub = stubs[Math.floorMod(next.getAndIncrement(), stubs.length)];
        return stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .echo(EchoMessage.newBuilder().setPayload(ByteString.copyFrom(payload)).build())
                .getPayload()
                .toByteArray();
    }
}
