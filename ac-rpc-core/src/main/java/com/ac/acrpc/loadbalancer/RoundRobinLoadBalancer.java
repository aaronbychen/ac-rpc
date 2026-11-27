package com.ac.acrpc.loadbalancer;

import com.ac.acrpc.model.ServiceMetaInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin load balancer
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

    /**
     * Current round-robin index
     */
    private final AtomicInteger currentIndex;

    public RoundRobinLoadBalancer() {
        this(0);
    }

    RoundRobinLoadBalancer(int start) {
        currentIndex = new AtomicInteger(start);
    }

    @Override
    public ServiceMetaInfo select(Map<String, Object> requestParams, List<ServiceMetaInfo> serviceMetaInfoList) {
        if (serviceMetaInfoList.isEmpty()) {
            return null;
        }
        // Single instance, no need to rotate
        int size = serviceMetaInfoList.size();
        if (size == 1) {
            return serviceMetaInfoList.get(0);
        }
        // floorMod keeps the index valid after the counter overflows to negative values
        int index = Math.floorMod(currentIndex.getAndIncrement(), size);
        return serviceMetaInfoList.get(index);
    }
}
