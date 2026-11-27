package com.ac.acrpc.loadbalancer;

import cn.hutool.core.util.HashUtil;
import com.ac.acrpc.model.ServiceMetaInfo;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Consistent-hash load balancer.
 *
 * <p>The ring is an immutable snapshot rebuilt only when the set of provider addresses changes,
 * so departed providers leave the ring and concurrent callers never see a half-built one.
 */
public class ConsistentHashLoadBalancer implements LoadBalancer {

    /**
     * Virtual nodes per provider
     */
    private static final int VIRTUAL_NODE_NUM = 100;

    private record Ring(Set<String> addresses, TreeMap<Integer, ServiceMetaInfo> nodes) {
    }

    private volatile Ring ring = new Ring(Set.of(), new TreeMap<>());

    @Override
    public ServiceMetaInfo select(Map<String, Object> requestParams, List<ServiceMetaInfo> serviceMetaInfoList) {
        if (serviceMetaInfoList.isEmpty()) {
            return null;
        }
        Ring current = ringFor(serviceMetaInfoList);
        Map.Entry<Integer, ServiceMetaInfo> entry = current.nodes().ceilingEntry(hash(String.valueOf(requestParams)));
        if (entry == null) {
            // Past the last virtual node: wrap around to the first
            entry = current.nodes().firstEntry();
        }
        return entry.getValue();
    }

    private Ring ringFor(List<ServiceMetaInfo> providers) {
        Set<String> addresses = providers.stream()
                .map(ServiceMetaInfo::getServiceAddress)
                .collect(Collectors.toUnmodifiableSet());
        Ring current = ring;
        if (current.addresses().equals(addresses)) {
            return current;
        }
        TreeMap<Integer, ServiceMetaInfo> nodes = new TreeMap<>();
        for (ServiceMetaInfo provider : providers) {
            for (int i = 0; i < VIRTUAL_NODE_NUM; i++) {
                nodes.put(hash(provider.getServiceAddress() + "#" + i), provider);
            }
        }
        Ring rebuilt = new Ring(addresses, nodes);
        ring = rebuilt;
        return rebuilt;
    }

    /**
     * MurmurHash3 spreads similar keys ("host#1", "host#2") across the ring; String.hashCode
     * clusters them, which skews load toward a few providers.
     */
    private static int hash(String key) {
        return HashUtil.murmur32(key.getBytes(StandardCharsets.UTF_8));
    }
}
