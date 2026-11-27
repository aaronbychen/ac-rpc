package com.ac.acrpc.springboot.starter.bootstrap;

import com.ac.acrpc.RpcApplication;
import com.ac.acrpc.config.RegistryConfig;
import com.ac.acrpc.config.RpcConfig;
import com.ac.acrpc.model.ServiceMetaInfo;
import com.ac.acrpc.registry.LocalRegistry;
import com.ac.acrpc.registry.Registry;
import com.ac.acrpc.registry.RegistryFactory;
import com.ac.acrpc.springboot.starter.annotation.RpcService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * RPC provider bootstrap.
 */
@Slf4j
public class RpcProviderBootstrap implements BeanPostProcessor {

    /**
     * Registers RPC services after bean initialization.
     *
     * @param bean
     * @param beanName
     * @return
     * @throws BeansException
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> beanClass = bean.getClass();
        RpcService rpcService = beanClass.getAnnotation(RpcService.class);
        if (rpcService != null) {
            // 1. Resolve service info
            Class<?> interfaceClass = rpcService.interfaceClass();
            // Default to the first implemented interface
            if (interfaceClass == void.class) {
                interfaceClass = beanClass.getInterfaces()[0];
            }
            String serviceName = interfaceClass.getName();
            String serviceVersion = rpcService.serviceVersion();
            // 2. Register locally
            LocalRegistry.register(serviceName, beanClass);

            final RpcConfig rpcConfig = RpcApplication.getRpcConfig();
            // 3. Register with the registry
            RegistryConfig registryConfig = rpcConfig.getRegistryConfig();
            Registry registry = RegistryFactory.getInstance(registryConfig.getRegistry());
            ServiceMetaInfo serviceMetaInfo = new ServiceMetaInfo();
            serviceMetaInfo.setServiceName(serviceName);
            serviceMetaInfo.setServiceVersion(serviceVersion);
            serviceMetaInfo.setServiceHost(rpcConfig.getServerHost());
            serviceMetaInfo.setServicePort(rpcConfig.getServerPort());
            try {
                registry.register(serviceMetaInfo);
            } catch (Exception e) {
                throw new RuntimeException(serviceName + " service registration failed", e);
            }
        }

        return BeanPostProcessor.super.postProcessAfterInitialization(bean, beanName);
    }
}
