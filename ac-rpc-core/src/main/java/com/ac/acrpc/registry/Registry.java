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
     * Keeps this provider's registrations alive (provider side). Implementations may do this
     * on their own once a service is registered.
     */
    void heartBeat();

    /**
     * Watches a service's providers and invalidates its cached list on any change (consumer side).
     * {@link #serviceDiscovery} starts this automatically.
     *
     * @param serviceKey service key
     */
    void watch(String serviceKey);

    /**
     * Destroy
     */
    void destroy();
}
