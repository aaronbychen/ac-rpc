package com.yupi.yurpc.server;

/**
 * HTTP server interface
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public interface HttpServer {

    /**
     * run server
     *
     * @param port
     */
    void doStart(int port);
}
