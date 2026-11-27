package com.ac.acrpc.registry;

import com.ac.acrpc.config.RegistryConfig;
import com.ac.acrpc.model.ServiceMetaInfo;

import java.util.List;

/**
 * Registry
 */
public interface Registry {

    /**
     * Initialize
     *
     * @param registryConfig
     */
    void init(RegistryConfig registryConfig);

    /**
     * Register a service (provider side)
     *
     * @param serviceMetaInfo
     */
    void register(ServiceMetaInfo serviceMetaInfo) throws Exception;

    /**
     * Unregister a service (provider side)
     *
     * @param serviceMetaInfo
     */
    void unRegister(ServiceMetaInfo serviceMetaInfo);

    /**
     * Discover all nodes of a service (consumer side)
     *
     * @param serviceKey service key
     * @return
     */
    List<ServiceMetaInfo> serviceDiscovery(String serviceKey);

    /**
     * Heartbeat (provider side)
     */
    void heartBeat();

    /**
     * Watch (consumer side)
     *
     * @param serviceNodeKey
     */
    void watch(String serviceNodeKey);

    /**
     * Destroy
     */
    void destroy();
}
