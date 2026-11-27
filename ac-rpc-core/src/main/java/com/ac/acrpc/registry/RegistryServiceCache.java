package com.ac.acrpc.registry;

import com.ac.acrpc.model.ServiceMetaInfo;

import java.util.List;

/**
 * Local cache of registry services
 */
public class RegistryServiceCache {

    /**
     * Service cache
     */
    List<ServiceMetaInfo> serviceCache;

    /**
     * Write cache
     *
     * @param newServiceCache
     * @return
     */
    void writeCache(List<ServiceMetaInfo> newServiceCache) {
        this.serviceCache = newServiceCache;
    }

    /**
     * Read cache
     *
     * @return
     */
    List<ServiceMetaInfo> readCache() {
        return this.serviceCache;
    }

    /**
     * Clear cache
     */
    void clearCache() {
        this.serviceCache = null;
    }
}
