package com.ac.acrpc.server.tcp;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServerOptions;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Vert.x TCP server.
 *
 * <p>Deploys one server instance per event loop on the same port; Vert.x spreads incoming
 * connections across them, so accepting and decoding are not limited to a single thread.
 */
@Slf4j
public class VertxTcpServer {

    private final Vertx vertx = Vertx.vertx();

    public void doStart(int port) {
        int instances = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        CompletableFuture<String> deployed = new CompletableFuture<>();
        vertx.deployVerticle(() -> new AbstractVerticle() {
                    @Override
                    public void start(Promise<Void> startPromise) {
                        vertx.createNetServer(new NetServerOptions().setTcpNoDelay(true))
                                .connectHandler(new TcpServerHandler())
                                .listen(port)
                                .<Void>mapEmpty()
                                .onComplete(startPromise);
                    }
                }, new DeploymentOptions().setInstances(instances))
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        deployed.complete(ar.result());
                    } else {
                        deployed.completeExceptionally(ar.cause());
                    }
                });
        try {
            deployed.get(30, TimeUnit.SECONDS);
            log.info("TCP server started on port {} with {} event loops", port, instances);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start TCP server on port " + port, e);
        }
    }

    public void stop() {
        vertx.close();
    }
}
