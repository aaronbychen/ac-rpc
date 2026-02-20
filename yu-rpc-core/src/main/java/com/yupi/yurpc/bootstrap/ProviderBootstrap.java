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
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public class ProviderBootstrap {

    /**
     * Init
     */
    public static void init(List<ServiceRegisterInfo<?>> serviceRegisterInfoList) {
        // RPC framework init (config and registry)
        RpcApplication.init();
        // Global config
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
                throw new RuntimeException(serviceName + " 服务注册失败", e);
            }
        }

        // Start the server
        VertxTcpServer vertxTcpServer = new VertxTcpServer();
        vertxTcpServer.doStart(rpcConfig.getServerPort());
    }
}
