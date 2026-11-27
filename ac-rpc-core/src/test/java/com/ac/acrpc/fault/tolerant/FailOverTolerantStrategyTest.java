package com.ac.acrpc.fault.tolerant;

import com.ac.acrpc.model.RpcResponse;
import com.ac.acrpc.model.ServiceMetaInfo;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FailOverTolerantStrategyTest {

    private final ServiceMetaInfo a = node(1);
    private final ServiceMetaInfo b = node(2);
    private final ServiceMetaInfo c = node(3);
    private final List<ServiceMetaInfo> tried = new ArrayList<>();

    @Test
    public void triesAnotherProviderAndReturnsItsResponse() {
        RpcResponse response = failOver(a, Set.of(a));
        assertEquals("ok", response.getData());
        assertFalse("retried the provider that already failed", tried.contains(a));
    }

    @Test
    public void skipsProvidersThatAlsoFail() {
        RpcResponse response = failOver(a, Set.of(a, b));
        assertEquals("ok", response.getData());
        assertTrue(tried.contains(c));
    }

    @Test
    public void failsWhenEveryProviderFails() {
        try {
            failOver(a, Set.of(a, b, c));
            fail("expected failure");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("all 3"));
        }
        assertEquals(2, tried.size());
    }

    @Test
    public void failsWhenThereIsNoOtherProvider() {
        try {
            new FailOverTolerantStrategy().doTolerant(context(a, List.of(a), Set.of()), new IOException("down"));
            fail("expected failure");
        } catch (RuntimeException expected) {
            assertTrue(tried.isEmpty());
        }
    }

    private RpcResponse failOver(ServiceMetaInfo failed, Set<ServiceMetaInfo> down) {
        return new FailOverTolerantStrategy().doTolerant(context(failed, List.of(a, b, c), down), new IOException("down"));
    }

    private Map<String, Object> context(ServiceMetaInfo failed, List<ServiceMetaInfo> candidates,
                                        Set<ServiceMetaInfo> down) {
        TolerantStrategy.Invoker invoker = node -> {
            tried.add(node);
            if (down.contains(node)) {
                throw new IOException(node.getServiceNodeKey() + " down");
            }
            RpcResponse response = new RpcResponse();
            response.setData("ok");
            return response;
        };
        Map<String, Object> context = new HashMap<>();
        context.put(TolerantStrategy.FAILED_NODE, failed);
        context.put(TolerantStrategy.CANDIDATES, candidates);
        context.put(TolerantStrategy.INVOKER, invoker);
        return context;
    }

    private static ServiceMetaInfo node(int i) {
        ServiceMetaInfo info = new ServiceMetaInfo();
        info.setServiceName("svc");
        info.setServiceHost("10.0.0." + i);
        info.setServicePort(8080);
        return info;
    }
}
