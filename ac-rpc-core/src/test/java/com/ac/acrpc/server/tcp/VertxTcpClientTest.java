package com.ac.acrpc.server.tcp;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VertxTcpClientTest {

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
    private VertxTcpClient client;

    @Before
    public void setUp() throws IOException {
        LocalRegistry.register(TestService.class.getName(), TestServiceImpl.class);
        port = freePort();
        server = new VertxTcpServer();
        server.doStart(port);
        client = new VertxTcpClient(1000, 2, 10_000);
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
        List<CompletableFuture<RpcResponse>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            futures.add(client.request(echo("v" + i), endpoint(port), "kryo", 5000));
        }
        for (int i = 0; i < n; i++) {
            assertEquals("v" + i, futures.get(i).get().getData());
        }
    }

    @Test
    public void slowCallTimesOutAndClientStaysUsable() throws Exception {
        RpcRequest slow = request("sleep", new Class<?>[]{long.class}, new Object[]{2000L});
        long start = System.nanoTime();
        try {
            client.request(slow, endpoint(port), "kryo", 200).get();
            fail("expected timeout");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof TimeoutException);
        }
        assertTrue(elapsedMs(start) < 1500);
        assertEquals("ok", client.request(echo("ok"), endpoint(port), "kryo", 1000).get().getData());
    }

    @Test
    public void unreachableEndpointFailsFast() throws Exception {
        int closedPort = freePort();
        long start = System.nanoTime();
        try {
            client.request(echo("x"), endpoint(closedPort), "kryo", 5000).get();
            fail("expected connection failure");
        } catch (ExecutionException e) {
            assertTrue(e.getCause().getMessage().contains("Cannot connect"));
        }
        assertTrue(elapsedMs(start) < 3000);
    }

    @Test
    public void reconnectsAfterServerRestart() throws Exception {
        assertEquals("a", client.request(echo("a"), endpoint(port), "kryo", 1000).get().getData());
        server.stop();
        server = null;
        try {
            client.request(echo("b"), endpoint(port), "kryo", 1000).get();
            fail("expected failure while server is down");
        } catch (ExecutionException expected) {
            // closed connection or refused reconnect
        }
        server = new VertxTcpServer();
        server.doStart(port);
        assertEquals("c", client.request(echo("c"), endpoint(port), "kryo", 1000).get().getData());
    }

    @Test
    public void threadCountDoesNotGrowWithCalls() throws Exception {
        client.request(echo("warm"), endpoint(port), "kryo", 1000).get();
        int before = ManagementFactory.getThreadMXBean().getThreadCount();
        for (int i = 0; i < 2000; i++) {
            client.request(echo("x"), endpoint(port), "kryo", 1000).get();
        }
        int after = ManagementFactory.getThreadMXBean().getThreadCount();
        assertTrue("threads grew from " + before + " to " + after, after - before < 20);
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
