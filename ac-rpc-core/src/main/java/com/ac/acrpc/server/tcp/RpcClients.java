package com.ac.acrpc.server.tcp;

import com.ac.acrpc.RpcApplication;
import com.ac.acrpc.config.RpcConfig;

/**
 * Process-wide client, built from the global RPC config on first use.
 */
public final class RpcClients {

    public static final String VERTX = "vertx";
    public static final String BLOCKING = "blocking";

    private static volatile RpcClient shared;

    private RpcClients() {
    }

    public static RpcClient shared() {
        if (shared == null) {
            synchronized (RpcClients.class) {
                if (shared == null) {
                    RpcClient client = create(RpcApplication.getRpcConfig());
                    Runtime.getRuntime().addShutdownHook(new Thread(client::close));
                    shared = client;
                }
            }
        }
        return shared;
    }

    public static RpcClient create(RpcConfig config) {
        return switch (config.getTransport()) {
            case VERTX -> new VertxTcpClient(config.getConnectTimeoutMs(), config.getConnectionsPerEndpoint(),
                    config.getMaxPendingPerConnection());
            case BLOCKING -> new BlockingTcpClient(config.getConnectTimeoutMs(), config.getConnectionsPerEndpoint(),
                    config.getMaxPendingPerConnection());
            default -> throw new IllegalArgumentException("Unknown transport: " + config.getTransport());
        };
    }
}
