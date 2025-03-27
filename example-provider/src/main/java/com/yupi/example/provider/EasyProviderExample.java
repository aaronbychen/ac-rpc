package com.yupi.example.provider;

import com.yupi.example.common.service.UserService;
import com.yupi.yurpc.registry.LocalRegistry;
import com.yupi.yurpc.server.HttpServer;
import com.yupi.yurpc.server.VertxHttpServer;

/**
 * Easy Service Provider Example
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public class EasyProviderExample {

    public static void main(String[] args) {
        // Register service
        LocalRegistry.register(UserService.class.getName(), UserServiceImpl.class);

        // Activate web service
        HttpServer httpServer = new VertxHttpServer();
        httpServer.doStart(8080);
    }
}
