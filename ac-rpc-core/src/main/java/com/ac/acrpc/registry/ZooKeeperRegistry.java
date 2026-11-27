package com.ac.acrpc.registry;

import com.ac.acrpc.config.RegistryConfig;
import com.ac.acrpc.model.ServiceMetaInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.x.discovery.ServiceDiscovery;
import org.apache.curator.x.discovery.ServiceDiscoveryBuilder;
import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.details.JsonInstanceSerializer;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * ZooKeeper registry
 * Docs: <a href="https://curator.apache.org/docs/getting-started">Apache Curator</a>
 * Example: <a href="https://github.com/apache/curator/blob/master/curator-examples/src/main/java/discovery/DiscoveryExample.java">DiscoveryExample.java</a>
 * Watch example: <a href="https://github.com/apache/curator/blob/master/curator-examples/src/main/java/cache/CuratorCacheExample.java">CuratorCacheExample.java</a>
 */
@Slf4j
public class ZooKeeperRegistry implements Registry {

    private CuratorFramework client;

    private ServiceDiscovery<ServiceMetaInfo> serviceDiscovery;

    /**
     * Nodes registered by this host
     */
    private final Set<String> localRegisterNodeKeySet = ConcurrentHashMap.newKeySet();

    /**
     * Service cache
     */
    private final RegistryServiceCache registryServiceCache = new RegistryServiceCache();

    /**
     * Watches by service key
     */
    private final ConcurrentMap<String, CuratorCache> watchers = new ConcurrentHashMap<>();

    /**
     * Root path
     */
    private static final String ZK_ROOT_PATH = "/rpc/zk";


    @Override
    public void init(RegistryConfig registryConfig) {
        // Build client
        client = CuratorFrameworkFactory
                .builder()
                .connectString(registryConfig.getAddress())
                .retryPolicy(new ExponentialBackoffRetry(Math.toIntExact(registryConfig.getTimeout()), 3))
                .build();

        // Build serviceDiscovery
        serviceDiscovery = ServiceDiscoveryBuilder.builder(ServiceMetaInfo.class)
                .client(client)
                .basePath(ZK_ROOT_PATH)
                .serializer(new JsonInstanceSerializer<>(ServiceMetaInfo.class))
                .build();

        try {
            // Start client and serviceDiscovery
            client.start();
            serviceDiscovery.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void register(ServiceMetaInfo serviceMetaInfo) throws Exception {
        // Register with ZooKeeper
        serviceDiscovery.registerService(buildServiceInstance(serviceMetaInfo));

        // Track the key locally
        String registerKey = ZK_ROOT_PATH + "/" + serviceMetaInfo.getServiceNodeKey();
        localRegisterNodeKeySet.add(registerKey);
    }

    @Override
    public void unRegister(ServiceMetaInfo serviceMetaInfo) {
        try {
            serviceDiscovery.unregisterService(buildServiceInstance(serviceMetaInfo));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Remove from local set
        String registerKey = ZK_ROOT_PATH + "/" + serviceMetaInfo.getServiceNodeKey();
        localRegisterNodeKeySet.remove(registerKey);
    }

    @Override
    public List<ServiceMetaInfo> serviceDiscovery(String serviceKey) {
        List<ServiceMetaInfo> cached = registryServiceCache.read(serviceKey);
        if (cached != null) {
            return cached;
        }
        long generation = registryServiceCache.generation(serviceKey);
        // Watch before reading, so a change after the read is never missed
        watch(serviceKey);
        try {
            // Query service instances
            Collection<ServiceInstance<ServiceMetaInfo>> serviceInstanceList = serviceDiscovery.queryForInstances(serviceKey);

            // Parse service info
            List<ServiceMetaInfo> serviceMetaInfoList = serviceInstanceList.stream()
                    .map(ServiceInstance::getPayload)
                    .collect(Collectors.toList());

            registryServiceCache.write(serviceKey, serviceMetaInfoList, generation);
            return serviceMetaInfoList;
        } catch (Exception e) {
            List<ServiceMetaInfo> stale = registryServiceCache.readStale(serviceKey);
            if (stale != null) {
                log.warn("Registry lookup for {} failed, using last known providers: {}", serviceKey, e.toString());
                return stale;
            }
            throw new RuntimeException("Failed to get service list for " + serviceKey, e);
        }
    }

    @Override
    public void heartBeat() {
        // No heartbeat needed: nodes are ephemeral and disappear if the server goes down
    }

    /**
     * Watches the service's subtree; any node change invalidates its cache entry.
     *
     * @param serviceKey service key
     */
    @Override
    public void watch(String serviceKey) {
        watchers.computeIfAbsent(serviceKey, key -> {
            CuratorCache cache = CuratorCache.build(client, ZK_ROOT_PATH + "/" + key);
            cache.listenable().addListener((type, oldData, data) -> registryServiceCache.invalidate(key));
            cache.start();
            return cache;
        });
    }

    @Override
    public void destroy() {
        log.info("Current node going offline");
        watchers.values().forEach(CuratorCache::close);
        watchers.clear();
        // Optional: ephemeral nodes disappear with the session anyway
        for (String key : localRegisterNodeKeySet) {
            try {
                client.delete().guaranteed().forPath(key);
            } catch (Exception e) {
                log.warn("Failed to remove {} on shutdown: {}", key, e.toString());
            }
        }

        // Release resources
        if (client != null) {
            client.close();
        }
    }

    private ServiceInstance<ServiceMetaInfo> buildServiceInstance(ServiceMetaInfo serviceMetaInfo) {
        String serviceAddress = serviceMetaInfo.getServiceHost() + ":" + serviceMetaInfo.getServicePort();
        try {
            return ServiceInstance
                    .<ServiceMetaInfo>builder()
                    .id(serviceAddress)
                    .name(serviceMetaInfo.getServiceKey())
                    .address(serviceAddress)
                    .payload(serviceMetaInfo)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
