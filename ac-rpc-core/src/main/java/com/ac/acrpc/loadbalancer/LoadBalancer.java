package com.ac.acrpc.loadbalancer;

import com.ac.acrpc.model.ServiceMetaInfo;

import java.util.List;
import java.util.Map;

/**
 * Load balancer (consumer side)
 */
public interface LoadBalancer {

    /**
     * Selects a service instance to call.
     *
     * @param requestParams       request parameters
     * @param serviceMetaInfoList available service instances
     * @return
     */
    ServiceMetaInfo select(Map<String, Object> requestParams, List<ServiceMetaInfo> serviceMetaInfoList);
}
