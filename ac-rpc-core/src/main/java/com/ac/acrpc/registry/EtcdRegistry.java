package com.ac.acrpc.registry;

import cn.hutool.json.JSONUtil;
import com.ac.acrpc.config.RegistryConfig;
import com.ac.acrpc.model.ServiceMetaInfo;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.support.CloseableClient;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * etcd registry.
 *
 * <p>Providers attach all their keys to one lease and keep it alive over etcd's keep-alive
 * stream. If the lease is lost (for example, etcd was unreachable for longer than the TTL), a
 * new lease is granted and every local key is written again.
 *
 * <p>Consumers watch each discovered service's key prefix, starting at the revision of the
 * lookup, so no change between the lookup and the watch is missed. Any change invalidates that
 * service's cache entry. Once a service has been seen, an invalidated entry is refreshed by a
 * single shared lookup that callers wait on for at most 0.5 s; if it is slow or failing, they get
 * the last known providers, so an etcd outage does not block traffic.
 */
@Slf4j
public class EtcdRegistry implements Registry {

    private static final String ETCD_ROOT_PATH = "/rpc/";
    private static final long REFRESH_BACKOFF_MIN_MS = 500;
    private static final long REFRESH_BACKOFF_MAX_MS = 5000;
    private static final long STALE_WAIT_MS = 500;

    private Client client;
    private KV kvClient;
    private Lease leaseClient;
    private Watch watchClient;
    private long timeoutMs;
    private long leaseTtlSeconds;

    /**
     * Keys registered by this process, with their values, for re-registration after lease loss
     */
    private final ConcurrentMap<String, String> localNodes = new ConcurrentHashMap<>();

    private final Object leaseLock = new Object();
    private long leaseId;
    private CloseableClient keepAlive;
    private final AtomicBoolean recovering = new AtomicBoolean();

    private final RegistryServiceCache registryServiceCache = new RegistryServiceCache();
    private final ConcurrentMap<String, Watch.Watcher> watchers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Refresh> refreshes = new ConcurrentHashMap<>();
    private volatile boolean destroyed;

    @Override
    public void init(RegistryConfig registryConfig) {
        timeoutMs = registryConfig.getTimeout();
        leaseTtlSeconds = registryConfig.getLeaseTtlSeconds();
        client = Client.builder()
                .endpoints(registryConfig.getAddress())
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        kvClient = client.getKVClient();
        leaseClient = client.getLeaseClient();
        watchClient = client.getWatchClient();
    }

    @Override
    public void register(ServiceMetaInfo serviceMetaInfo) throws Exception {
        String key = ETCD_ROOT_PATH + serviceMetaInfo.getServiceNodeKey();
        String value = JSONUtil.toJsonStr(serviceMetaInfo);
        localNodes.put(key, value);
        try {
            put(key, value, currentLease());
        } catch (Exception e) {
            localNodes.remove(key);
            throw e;
        }
    }

    @Override
    public void unRegister(ServiceMetaInfo serviceMetaInfo) {
        String key = ETCD_ROOT_PATH + serviceMetaInfo.getServiceNodeKey();
        localNodes.remove(key);
        try {
            kvClient.delete(bytes(key)).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to unregister " + key, e);
        }
    }

    /**
     * Fresh providers if the cache has them. Otherwise, if providers were seen before, waits at
     * most {@link #STALE_WAIT_MS} for a single shared lookup and then falls back to the last known
     * list, and does not wait at all while lookups are failing. A membership change is therefore
     * picked up at once while etcd answers, and an outage costs callers at most one short wait.
     * Only the very first lookup of a service waits for the full registry timeout.
     */
    @Override
    public List<ServiceMetaInfo> serviceDiscovery(String serviceKey) {
        List<ServiceMetaInfo> cached = registryServiceCache.read(serviceKey);
        if (cached != null) {
            return cached;
        }
        List<ServiceMetaInfo> stale = registryServiceCache.readStale(serviceKey);
        if (stale != null) {
            Pending pending = refresh(serviceKey, false);
            if (pending == null || pending.afterFailure()) {
                return stale;
            }
            long remainingNs = pending.startedNs() + TimeUnit.MILLISECONDS.toNanos(STALE_WAIT_MS) - System.nanoTime();
            if (remainingNs <= 0) {
                return stale;
            }
            try {
                return pending.lookup().get(remainingNs, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return stale;
            } catch (ExecutionException | TimeoutException e) {
                return stale;
            }
        }
        try {
            return refresh(serviceKey, true).lookup().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Failed to get service list for " + serviceKey, cause);
        }
    }

    /**
     * Starts a lookup of the service unless one is already running, and returns it. After a
     * failed lookup, further lookups back off (0.5 s doubling to 5 s) so an etcd outage does not
     * turn into a request per call; returns null when a lookup is not due yet.
     */
    private Pending refresh(String serviceKey, boolean force) {
        Refresh state = refreshes.computeIfAbsent(serviceKey, key -> new Refresh());
        synchronized (state) {
            if (state.inFlight != null) {
                return state.inFlight;
            }
            if (!force && System.nanoTime() < state.retryAtNs) {
                return null;
            }
            long generation = registryServiceCache.generation(serviceKey);
            CompletableFuture<List<ServiceMetaInfo>> lookup;
            try {
                lookup = kvClient.get(bytes(prefix(serviceKey)), GetOption.builder().isPrefix(true).build())
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .thenApply(response -> {
                            List<ServiceMetaInfo> nodes = response.getKvs().stream()
                                    .map(kv -> JSONUtil.toBean(kv.getValue().toString(StandardCharsets.UTF_8),
                                            ServiceMetaInfo.class))
                                    .toList();
                            watchService(serviceKey, response.getHeader().getRevision() + 1);
                            registryServiceCache.write(serviceKey, nodes, generation);
                            return nodes;
                        });
            } catch (RuntimeException e) {
                // A closed client throws instead of returning a failed future
                lookup = CompletableFuture.failedFuture(e);
            }
            Pending pending = new Pending(lookup, System.nanoTime(), state.failing);
            state.inFlight = pending;
            lookup.whenComplete((nodes, error) -> {
                synchronized (state) {
                    state.inFlight = null;
                    state.failing = error != null;
                    if (error == null) {
                        state.backoffMs = REFRESH_BACKOFF_MIN_MS;
                        state.retryAtNs = 0;
                    } else {
                        state.retryAtNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(state.backoffMs);
                        log.warn("Registry lookup for {} failed, using last known providers; retrying in {} ms: {}",
                                serviceKey, state.backoffMs, error.toString());
                        state.backoffMs = Math.min(state.backoffMs * 2, REFRESH_BACKOFF_MAX_MS);
                    }
                }
            });
            return pending;
        }
    }

    /**
     * A lookup in flight, when it started, and whether the lookup before it failed.
     */
    private record Pending(CompletableFuture<List<ServiceMetaInfo>> lookup, long startedNs, boolean afterFailure) {
    }

    private static final class Refresh {
        Pending inFlight;
        boolean failing;
        long retryAtNs;
        long backoffMs = REFRESH_BACKOFF_MIN_MS;
    }

    /**
     * Nothing to schedule: the lease keep-alive stream starts with the first registration.
     */
    @Override
    public void heartBeat() {
    }

    @Override
    public void watch(String serviceKey) {
        watchService(serviceKey, 0);
    }

    @Override
    public void destroy() {
        destroyed = true;
        watchers.values().forEach(Watch.Watcher::close);
        watchers.clear();
        for (String key : localNodes.keySet()) {
            try {
                kvClient.delete(bytes(key)).get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("Failed to remove {} on shutdown; it expires with its lease: {}", key, e.toString());
            }
        }
        localNodes.clear();
        synchronized (leaseLock) {
            if (keepAlive != null) {
                keepAlive.close();
            }
            if (leaseId != 0) {
                try {
                    leaseClient.revoke(leaseId).get(timeoutMs, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    log.warn("Failed to revoke lease {}: {}", leaseId, e.toString());
                }
                leaseId = 0;
            }
        }
        if (client != null) {
            client.close();
        }
    }

    /**
     * The lease shared by all local keys, granted and kept alive on first use.
     */
    private long currentLease() throws Exception {
        synchronized (leaseLock) {
            if (leaseId == 0) {
                long id = leaseClient.grant(leaseTtlSeconds).get(timeoutMs, TimeUnit.MILLISECONDS).getID();
                keepAlive = leaseClient.keepAlive(id, new KeepAliveObserver(id));
                leaseId = id;
            }
            return leaseId;
        }
    }

    private void put(String key, String value, long lease) throws Exception {
        kvClient.put(bytes(key), bytes(value), PutOption.builder().withLeaseId(lease).build())
                .get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void onLeaseLost(long lostLeaseId, Throwable cause) {
        synchronized (leaseLock) {
            if (leaseId != lostLeaseId) {
                return;
            }
            leaseId = 0;
            if (keepAlive != null) {
                keepAlive.close();
                keepAlive = null;
            }
        }
        if (destroyed) {
            return;
        }
        log.warn("etcd lease {} lost, re-registering {} key(s): {}", lostLeaseId, localNodes.size(),
                cause == null ? "stream completed" : cause.toString());
        if (recovering.compareAndSet(false, true)) {
            Thread.ofVirtual().name("acrpc-etcd-recovery").start(this::reRegister);
        }
    }

    /**
     * Grants a new lease and writes every local key again, retrying with backoff until it works.
     */
    private void reRegister() {
        long backoffMs = 200;
        try {
            while (!destroyed) {
                try {
                    long lease = currentLease();
                    for (Map.Entry<String, String> node : localNodes.entrySet()) {
                        put(node.getKey(), node.getValue(), lease);
                    }
                    log.info("Re-registered {} key(s) under lease {}", localNodes.size(), lease);
                    return;
                } catch (Exception e) {
                    log.warn("Re-registration failed, retrying in {} ms: {}", backoffMs, e.toString());
                    Thread.sleep(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, 5000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            recovering.set(false);
        }
    }

    /**
     * Watches a service's prefix from {@code fromRevision} (0 means now); every change invalidates
     * its cache entry. A failed watch is dropped so the next lookup starts a new one.
     */
    private void watchService(String serviceKey, long fromRevision) {
        watchers.computeIfAbsent(serviceKey, key -> {
            WatchOption option = WatchOption.builder()
                    .isPrefix(true)
                    .withRevision(fromRevision)
                    .build();
            return watchClient.watch(bytes(prefix(key)), option,
                    response -> registryServiceCache.invalidate(key),
                    error -> {
                        log.warn("Watch on {} failed: {}", key, error.toString());
                        Watch.Watcher failed = watchers.remove(key);
                        if (failed != null) {
                            failed.close();
                        }
                        registryServiceCache.invalidate(key);
                    });
        });
    }

    private final class KeepAliveObserver implements StreamObserver<LeaseKeepAliveResponse> {

        private final long id;

        KeepAliveObserver(long id) {
            this.id = id;
        }

        @Override
        public void onNext(LeaseKeepAliveResponse response) {
            if (response.getTTL() <= 0) {
                onLeaseLost(id, null);
            }
        }

        @Override
        public void onError(Throwable t) {
            onLeaseLost(id, t);
        }

        @Override
        public void onCompleted() {
            onLeaseLost(id, null);
        }
    }

    private static String prefix(String serviceKey) {
        // The trailing slash keeps "svc:1.0" from matching "svc:1.0.1"
        return ETCD_ROOT_PATH + serviceKey + "/";
    }

    private static ByteSequence bytes(String s) {
        return ByteSequence.from(s, StandardCharsets.UTF_8);
    }

    /**
     * Visible for tests: drops the connection without cleanup, as if the process died.
     */
    void crashForTest() {
        destroyed = true;
        client.close();
    }

    RegistryServiceCache registryServiceCacheForTest() {
        return registryServiceCache;
    }

    /**
     * Visible for tests: the lease currently holding this process's keys, or 0.
     */
    long leaseIdForTest() {
        synchronized (leaseLock) {
            return leaseId;
        }
    }
}
