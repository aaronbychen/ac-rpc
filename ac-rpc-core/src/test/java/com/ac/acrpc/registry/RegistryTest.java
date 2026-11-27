package com.ac.acrpc.registry;

import com.ac.acrpc.config.RegistryConfig;
import com.ac.acrpc.model.ServiceMetaInfo;
import io.etcd.jetcd.Client;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests against etcd on localhost:2379. Each test uses fresh service names.
 */
public class RegistryTest {

    private static final String ETCD = "http://localhost:2379";

    private final List<EtcdRegistry> registries = new ArrayList<>();
    private final String prefix = "test-" + UUID.randomUUID().toString().substring(0, 8) + "-";

    @After
    public void tearDown() {
        for (EtcdRegistry registry : registries) {
            try {
                registry.destroy();
            } catch (Exception ignored) {
                // already crashed in the test
            }
        }
    }

    @Test
    public void discoveryIsKeyedByService() throws Exception {
        EtcdRegistry provider = registry(30);
        provider.register(node("a", 1001));
        provider.register(node("b", 1002));
        EtcdRegistry consumer = registry(30);

        assertEquals(List.of(1001), ports(consumer.serviceDiscovery(key("a"))));
        // The old single-list cache returned service a's providers here
        assertEquals(List.of(1002), ports(consumer.serviceDiscovery(key("b"))));
    }

    @Test
    public void consumerSeesProvidersJoinAndLeave() throws Exception {
        EtcdRegistry provider = registry(30);
        provider.register(node("a", 1001));
        EtcdRegistry consumer = registry(30);
        assertEquals(1, consumer.serviceDiscovery(key("a")).size());

        provider.register(node("a", 1002));
        await("new provider seen", () -> consumer.serviceDiscovery(key("a")).size() == 2);

        provider.unRegister(node("a", 1001));
        await("departed provider dropped", () -> ports(consumer.serviceDiscovery(key("a"))).equals(List.of(1002)));
    }

    @Test
    public void keepAliveHoldsKeysPastTheirTtl() throws Exception {
        EtcdRegistry provider = registry(2);
        provider.register(node("a", 1001));
        TimeUnit.SECONDS.sleep(5);
        assertEquals(1, registry(30).serviceDiscovery(key("a")).size());
    }

    @Test
    public void crashedProviderExpiresAfterItsTtl() throws Exception {
        EtcdRegistry provider = registry(2);
        provider.register(node("a", 1001));
        EtcdRegistry consumer = registry(30);
        assertEquals(1, consumer.serviceDiscovery(key("a")).size());

        provider.crashForTest();
        await("crashed provider expired", () -> consumer.serviceDiscovery(key("a")).isEmpty());
    }

    @Test
    public void providerReRegistersAfterLosingItsLease() throws Exception {
        // Loss is noticed at the next keep-alive, sent every TTL/3; a short TTL keeps the test fast
        EtcdRegistry provider = registry(3);
        provider.register(node("a", 1001));
        provider.register(node("b", 1002));
        long lease = provider.leaseIdForTest();
        EtcdRegistry consumer = registry(30);
        assertEquals(1, consumer.serviceDiscovery(key("a")).size());

        // Revoking the lease deletes its keys, as if etcd had expired it during an outage
        try (Client admin = Client.builder().endpoints(ETCD).build()) {
            admin.getLeaseClient().revoke(lease).get(5, TimeUnit.SECONDS);
        }
        await("keys restored under a new lease", () -> provider.leaseIdForTest() != 0
                && provider.leaseIdForTest() != lease
                && consumer.serviceDiscovery(key("a")).size() == 1
                && consumer.serviceDiscovery(key("b")).size() == 1);
    }

    @Test
    public void consumerKeepsLastKnownProvidersWhenEtcdIsUnreachable() throws Exception {
        EtcdRegistry provider = registry(30);
        provider.register(node("a", 1001));
        EtcdRegistry consumer = registry(30);
        assertEquals(1, consumer.serviceDiscovery(key("a")).size());

        consumer.crashForTest();
        consumer.registryServiceCacheForTest().invalidate(key("a"));
        assertEquals(List.of(1001), ports(consumer.serviceDiscovery(key("a"))));
    }

    @Test
    public void twoRegistriesCanRunInOneJvm() throws Exception {
        // The old implementation started a global cron scheduler in init(), so a second init threw
        registry(30).register(node("a", 1001));
        registry(30).register(node("a", 1002));
        assertEquals(2, registry(30).serviceDiscovery(key("a")).size());
    }

    private EtcdRegistry registry(long ttlSeconds) {
        RegistryConfig config = new RegistryConfig();
        config.setAddress(ETCD);
        config.setTimeout(3000L);
        config.setLeaseTtlSeconds(ttlSeconds);
        EtcdRegistry registry = new EtcdRegistry();
        registry.init(config);
        registries.add(registry);
        return registry;
    }

    private ServiceMetaInfo node(String service, int port) {
        ServiceMetaInfo info = new ServiceMetaInfo();
        info.setServiceName(prefix + service);
        info.setServiceVersion("1.0");
        info.setServiceHost("localhost");
        info.setServicePort(port);
        return info;
    }

    private String key(String service) {
        return node(service, 0).getServiceKey();
    }

    private static List<Integer> ports(List<ServiceMetaInfo> nodes) {
        return nodes.stream().map(ServiceMetaInfo::getServicePort).sorted().toList();
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertTrue("timed out waiting: " + what, condition.getAsBoolean());
    }
}
