package com.ac.acrpc.server;

/**
 * HTTP 服务器接口
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public interface HttpServer {

    /**
     * 启动服务器
     *
     * @param port
     */
    void doStart(int port);
}
