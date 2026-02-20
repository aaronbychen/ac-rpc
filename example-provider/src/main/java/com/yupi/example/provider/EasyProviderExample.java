package com.ac.example.provider;

import com.ac.example.common.service.UserService;
import com.ac.acrpc.registry.LocalRegistry;
import com.ac.acrpc.server.HttpServer;
import com.ac.acrpc.server.VertxHttpServer;

/**
 * 简易服务提供者示例
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public class EasyProviderExample {

    public static void main(String[] args) {
        // 注册服务
        LocalRegistry.register(UserService.class.getName(), UserServiceImpl.class);

        // 启动 web 服务
        HttpServer httpServer = new VertxHttpServer();
        httpServer.doStart(8080);
    }
}
