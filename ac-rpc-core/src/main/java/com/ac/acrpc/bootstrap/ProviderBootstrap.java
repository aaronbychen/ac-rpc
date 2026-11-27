package com.ac.acrpc.bootstrap;

import com.ac.acrpc.RpcApplication;
import com.ac.acrpc.config.RegistryConfig;
import com.ac.acrpc.config.RpcConfig;
import com.ac.acrpc.model.ServiceMetaInfo;
import com.ac.acrpc.model.ServiceRegisterInfo;
import com.ac.acrpc.registry.LocalRegistry;
import com.ac.acrpc.registry.Registry;
import com.ac.acrpc.registry.RegistryFactory;
import com.ac.acrpc.server.tcp.VertxTcpServer;

import java.util.List;

/**
 * Service provider startup class (init)
 */
public class ProviderBootstrap {

    /**
     * Init with config loaded from application.properties
     */
    public static void init(List<ServiceRegisterInfo<?>> serviceRegisterInfoList) {
        RpcApplication.init();
        registerAndStart(serviceRegisterInfoList);
    }

    /**
     * Init with an explicit config
     */
    public static void init(RpcConfig rpcConfig, List<ServiceRegisterInfo<?>> serviceRegisterInfoList) {
        RpcApplication.init(rpcConfig);
        registerAndStart(serviceRegisterInfoList);
    }

    private static void registerAndStart(List<ServiceRegisterInfo<?>> serviceRegisterInfoList) {
        final RpcConfig rpcConfig = RpcApplication.getRpcConfig();

        // Registration services
        for (ServiceRegisterInfo<?> serviceRegisterInfo : serviceRegisterInfoList) {
            String serviceName = serviceRegisterInfo.getServiceName();
            // Local registration
            LocalRegistry.register(serviceName, serviceRegisterInfo.getImplClass());

            // Register for services at the registration center
            RegistryConfig registryConfig = rpcConfig.getRegistryConfig();
            Registry registry = RegistryFactory.getInstance(registryConfig.getRegistry());
            ServiceMetaInfo serviceMetaInfo = new ServiceMetaInfo();
            serviceMetaInfo.setServiceName(serviceName);
            serviceMetaInfo.setServiceHost(rpcConfig.getServerHost());
            serviceMetaInfo.setServicePort(rpcConfig.getServerPort());
            try {
                registry.register(serviceMetaInfo);
            } catch (Exception e) {
                throw new RuntimeException(serviceName + " service registration failed", e);
            }
        }

        // Start the server
        VertxTcpServer vertxTcpServer = new VertxTcpServer();
        vertxTcpServer.doStart(rpcConfig.getServerPort());
    }
}
