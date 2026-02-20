package com.ac.example.provider;

import com.ac.example.common.service.UserService;
import com.ac.acrpc.registry.LocalRegistry;
import com.ac.acrpc.server.HttpServer;
import com.ac.acrpc.server.VertxHttpServer;

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
