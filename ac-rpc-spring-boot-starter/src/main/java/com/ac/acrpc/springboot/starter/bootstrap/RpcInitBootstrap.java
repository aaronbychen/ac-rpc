package com.ac.acrpc.springboot.starter.bootstrap;

import com.ac.acrpc.RpcApplication;
import com.ac.acrpc.config.RpcConfig;
import com.ac.acrpc.server.tcp.VertxTcpServer;
import com.ac.acrpc.springboot.starter.annotation.EnableRpc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * RPC framework bootstrap.
 */
@Slf4j
public class RpcInitBootstrap implements ImportBeanDefinitionRegistrar {

    /**
     * Initializes the RPC framework during Spring startup.
     *
     * @param importingClassMetadata
     * @param registry
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        // Read EnableRpc attributes
        boolean needServer = (boolean) importingClassMetadata.getAnnotationAttributes(EnableRpc.class.getName())
                .get("needServer");

        // Initialize RPC config and registry
        RpcApplication.init();

        final RpcConfig rpcConfig = RpcApplication.getRpcConfig();

        // Start the server
        if (needServer) {
            VertxTcpServer vertxTcpServer = new VertxTcpServer();
            vertxTcpServer.doStart(rpcConfig.getServerPort());
        } else {
            log.info("Server startup disabled");
        }

    }
}
