package com.ac.acrpc.loadbalancer;

import com.ac.acrpc.model.ServiceMetaInfo;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class LoadBalancerTest {

    private static final ServiceMetaInfo A = node("10.0.0.1", 8080);
    private static final ServiceMetaInfo B = node("10.0.0.2", 8080);
    private static final ServiceMetaInfo C = node("10.0.0.3", 8080);

    @Test
    public void roundRobinVisitsEveryNodeInTurn() {
        LoadBalancer lb = new RoundRobinLoadBalancer();
        List<ServiceMetaInfo> nodes = List.of(A, B, C);
        for (int i = 0; i < 9; i++) {
            assertEquals(nodes.get(i % 3), lb.select(Map.of(), nodes));
        }
    }

    @Test
    public void roundRobinStaysInRangeAfterCounterOverflow() {
        LoadBalancer lb = new RoundRobinLoadBalancer(Integer.MAX_VALUE - 3);
        List<ServiceMetaInfo> nodes = List.of(A, B, C);
        Set<ServiceMetaInfo> seen = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            seen.add(lb.select(Map.of(), nodes));
        }
        assertEquals(Set.of(A, B, C), seen);
    }

    @Test
    public void consistentHashIsStableForTheSameKey() {
        LoadBalancer lb = new ConsistentHashLoadBalancer();
        List<ServiceMetaInfo> nodes = List.of(A, B, C);
        ServiceMetaInfo first = lb.select(key(42), nodes);
        for (int i = 0; i < 10; i++) {
            assertEquals(first, lb.select(key(42), nodes));
        }
    }

    @Test
    public void consistentHashOnlyMovesKeysOfADepartedNode() {
        LoadBalancer lb = new ConsistentHashLoadBalancer();
        Map<Integer, ServiceMetaInfo> before = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            before.put(i, lb.select(key(i), List.of(A, B, C)));
        }
        for (int i = 0; i < 2000; i++) {
            ServiceMetaInfo after = lb.select(key(i), List.of(A, B));
            assertNotEquals("departed node still selected", C, after);
            if (!before.get(i).equals(C)) {
                assertEquals("key " + i + " moved although its node stayed", before.get(i), after);
            }
        }
    }

    @Test
    public void consistentHashSpreadsKeysAcrossNodes() {
        LoadBalancer lb = new ConsistentHashLoadBalancer();
        Map<ServiceMetaInfo, Integer> counts = new HashMap<>();
        int keys = 6000;
        for (int i = 0; i < keys; i++) {
            counts.merge(lb.select(key(i), List.of(A, B, C)), 1, Integer::sum);
        }
        for (ServiceMetaInfo node : List.of(A, B, C)) {
            int share = counts.getOrDefault(node, 0) * 100 / keys;
            assertTrue(node.getServiceHost() + " got " + share + "% of keys", share >= 20 && share <= 47);
        }
    }

    @Test
    public void consistentHashOnlyReturnsNodesFromTheGivenListUnderConcurrentChanges() throws Exception {
        LoadBalancer lb = new ConsistentHashLoadBalancer();
        List<ServiceMetaInfo> left = List.of(A, B);
        List<ServiceMetaInfo> right = List.of(C);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                List<ServiceMetaInfo> nodes = t % 2 == 0 ? left : right;
                tasks.add(executor.submit(() -> {
                    for (int i = 0; i < 5000; i++) {
                        ServiceMetaInfo selected = lb.select(key(i), nodes);
                        assertTrue("selected " + selected.getServiceHost() + " outside the given list",
                                nodes.contains(selected));
                    }
                }));
            }
            for (Future<?> task : tasks) {
                task.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static Map<String, Object> key(int i) {
        return Map.of("methodName", "method" + i);
    }

    private static ServiceMetaInfo node(String host, int port) {
        ServiceMetaInfo info = new ServiceMetaInfo();
        info.setServiceName("svc");
        info.setServiceHost(host);
        info.setServicePort(port);
        return info;
    }
}
