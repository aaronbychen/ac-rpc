package com.ac.acrpc.bench;

import com.ac.acrpc.bench.grpc.EchoGrpc;
import com.ac.acrpc.bench.grpc.EchoMessage;
import com.ac.acrpc.bootstrap.ProviderBootstrap;
import com.ac.acrpc.model.ServiceRegisterInfo;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * Starts an echo server for the selected system.
 */
final class BenchProvider {

    static void run(Options options) throws Exception {
        switch (Main.system(options)) {
            case "acrpc" -> ProviderBootstrap.init(Main.rpcConfig(options),
                    List.of(new ServiceRegisterInfo<>(EchoService.class.getName(), EchoServiceImpl.class)));
            case "grpc" -> startGrpc(options.getInt("port", 8080), options.get("grpc-executor", "virtual"));
            default -> throw new IllegalArgumentException("Unknown --system " + Main.system(options));
        }
    }

    /**
     * Plaintext gRPC server. With {@code virtual}, handlers run on virtual threads, matching
     * ac-rpc's dispatch. With {@code direct}, they run on the Netty event loop, gRPC's
     * recommended setting for non-blocking handlers.
     */
    private static void startGrpc(int port, String executor) throws Exception {
        NettyServerBuilder builder = NettyServerBuilder.forPort(port);
        switch (executor) {
            case "virtual" -> builder.executor(Executors.newVirtualThreadPerTaskExecutor());
            case "direct" -> builder.directExecutor();
            default -> throw new IllegalArgumentException("Unknown --grpc-executor " + executor);
        }
        Server server = builder
                .addService(new EchoGrpc.EchoImplBase() {
                    @Override
                    public void echo(EchoMessage request, StreamObserver<EchoMessage> responseObserver) {
                        responseObserver.onNext(request);
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdownNow));
        server.awaitTermination();
    }
}
