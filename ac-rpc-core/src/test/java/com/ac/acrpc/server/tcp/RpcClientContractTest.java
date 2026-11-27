package com.ac.acrpc.server.tcp;

import com.ac.acrpc.exception.RpcException;
import com.ac.acrpc.model.RpcRequest;
import com.ac.acrpc.model.RpcResponse;
import com.ac.acrpc.model.ServiceMetaInfo;
import com.ac.acrpc.registry.LocalRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Behaviour every {@link RpcClient} must provide, run against a real in-process server.
 */
public abstract class RpcClientContractTest {

    protected abstract RpcClient newClient();


    public interface TestService {
        String echo(String value);

        String sleep(long millis);
    }

    public static class TestServiceImpl implements TestService {
        @Override
        public String echo(String value) {
            return value;
        }

        @Override
        public String sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "slept";
        }
    }

    private int port;
    private VertxTcpServer server;
    private RpcClient client;

    @Before
    public void setUp() throws IOException {
        LocalRegistry.register(TestService.class.getName(), TestServiceImpl.class);
        port = freePort();
        server = new VertxTcpServer();
        server.doStart(port);
        client = newClient();
    }

    @After
    public void tearDown() {
        client.close();
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void concurrentRequestsAreMatchedToTheirResponses() throws Exception {
        int n = 2000;
        List<Future<RpcResponse>> futures = new ArrayList<>(n);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                RpcRequest request = echo("v" + i);
                futures.add(executor.submit(() -> client.call(request, endpoint(port), "kryo", 5000)));
            }
            for (int i = 0; i < n; i++) {
                assertEquals("v" + i, futures.get(i).get().getData());
            }
        }
    }

    @Test
    public void slowCallTimesOutAndClientStaysUsable() throws Exception {
        RpcRequest slow = request("sleep", new Class<?>[]{long.class}, new Object[]{2000L});
        long start = System.nanoTime();
        try {
            client.call(slow, endpoint(port), "kryo", 200);
            fail("expected timeout");
        } catch (TimeoutException expected) {
            // deadline passed
        }
        assertTrue(elapsedMs(start) < 1500);
        assertEquals("ok", client.call(echo("ok"), endpoint(port), "kryo", 1000).getData());
    }

    @Test
    public void unreachableEndpointFailsFast() throws Exception {
        int closedPort = freePort();
        long start = System.nanoTime();
        try {
            client.call(echo("x"), endpoint(closedPort), "kryo", 5000);
            fail("expected connection failure");
        } catch (RpcException e) {
            assertTrue(e.getMessage().contains("Cannot connect"));
        }
        assertTrue(elapsedMs(start) < 3000);
    }

    @Test
    public void reconnectsAfterServerRestart() throws Exception {
        assertEquals("a", client.call(echo("a"), endpoint(port), "kryo", 1000).getData());
        server.stop();
        server = null;
        try {
            client.call(echo("b"), endpoint(port), "kryo", 1000);
            fail("expected failure while server is down");
        } catch (RpcException | TimeoutException expected) {
            // closed connection, refused reconnect, or a write into a socket not yet seen as dead
        }
        server = new VertxTcpServer();
        server.doStart(port);
        assertEquals("c", client.call(echo("c"), endpoint(port), "kryo", 1000).getData());
    }

    @Test
    public void threadCountDoesNotGrowWithCalls() throws Exception {
        client.call(echo("warm"), endpoint(port), "kryo", 1000);
        int before = ManagementFactory.getThreadMXBean().getThreadCount();
        for (int i = 0; i < 2000; i++) {
            client.call(echo("x"), endpoint(port), "kryo", 1000);
        }
        int after = ManagementFactory.getThreadMXBean().getThreadCount();
        assertTrue("threads grew from " + before + " to " + after, after - before < 20);
    }

    /**
     * A peer that accepts but never reads lets socket buffers fill until writes block. Calls
     * must still fail at their deadline instead of hanging behind the blocked write.
     */
    @Test
    public void deadlineHoldsWhenPeerStopsReading() throws Exception {
        List<java.net.Socket> accepted = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        // Hold a reference: an unreferenced socket can be closed by GC, resetting the peer
                        accepted.add(silent.accept());
                    }
                } catch (Exception ignored) {
                    // server socket closed at the end of the test
                }
            });
            String big = "x".repeat(1 << 20);
            int calls = 32;
            List<Future<Long>> durations = new ArrayList<>(calls);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < calls; i++) {
                    durations.add(executor.submit(() -> {
                        long start = System.nanoTime();
                        try {
                            client.call(echo(big), endpoint(silent.getLocalPort()), "kryo", 300);
                            fail("expected timeout");
                        } catch (TimeoutException expected) {
                            // deadline passed
                        }
                        return elapsedMs(start);
                    }));
                }
                for (Future<Long> d : durations) {
                    long ms = d.get(10, TimeUnit.SECONDS);
                    assertTrue("call took " + ms + " ms with a 300 ms deadline", ms < 1500);
                }
            }
        } finally {
            for (java.net.Socket s : accepted) {
                s.close();
            }
        }
    }

    private static RpcRequest echo(String value) {
        return request("echo", new Class<?>[]{String.class}, new Object[]{value});
    }

    private static RpcRequest request(String method, Class<?>[] types, Object[] args) {
        return RpcRequest.builder()
                .serviceName(TestService.class.getName())
                .methodName(method)
                .parameterTypes(types)
                .args(args)
                .build();
    }

    private static ServiceMetaInfo endpoint(int port) {
        ServiceMetaInfo info = new ServiceMetaInfo();
        info.setServiceHost("127.0.0.1");
        info.setServicePort(port);
        return info;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
