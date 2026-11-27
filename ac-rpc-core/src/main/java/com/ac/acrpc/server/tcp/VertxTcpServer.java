package com.ac.acrpc.server.tcp;

import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import lombok.extern.slf4j.Slf4j;

/**
 * Vert.x TCP server
 */
@Slf4j
public class VertxTcpServer {

    public void doStart(int port) {
        // Create the Vert.x instance
        Vertx vertx = Vertx.vertx();

        // Create the TCP server
        NetServer server = vertx.createNetServer();

        // Handle requests
        server.connectHandler(new TcpServerHandler());

        // Start the server and listen on the port
        server.listen(port, result -> {
            if (result.succeeded()) {
                log.info("TCP server started on port " + port);
            } else {
                log.info("Failed to start TCP server: " + result.cause());
            }
        });
    }

    public static void main(String[] args) {
        new VertxTcpServer().doStart(8888);
    }
}
