package com.ac.acrpc.proxy;

import com.ac.acrpc.RpcApplication;
import com.ac.acrpc.config.RpcConfig;
import com.ac.acrpc.fault.tolerant.TolerantStrategyKeys;
import com.ac.acrpc.model.ServiceMetaInfo;
import com.ac.acrpc.registry.LocalRegistry;
import com.ac.acrpc.registry.Registry;
import com.ac.acrpc.registry.RegistryFactory;
import com.ac.acrpc.serializer.SerializerKeys;
import com.ac.acrpc.server.tcp.VertxTcpServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Two providers registered in etcd (localhost:2379); one crashes while the registry still lists it.
 */
public class FailoverIntegrationTest {

    public interface Greeter {
        String greet(String name);

        String fail(String message);
    }

    public static class GreeterImpl implements Greeter {
        @Override
        public String greet(String name) {
            return "hello " + name;
        }

        @Override
        public String fail(String message) {
            throw new IllegalStateException(message);
        }
    }

    private static RpcConfig config;
    private static Registry registry;
    private static VertxTcpServer survivor;

    @BeforeClass
    public static void setUp() throws Exception {
        config = new RpcConfig();
        config.setSerializer(SerializerKeys.KRYO);
        config.setRequestTimeoutMs(1000);
        config.setConnectTimeoutMs(500);
        config.getRegistryConfig().setAddress("http://localhost:2379");
        RpcApplication.init(config);
        registry = RegistryFactory.getInstance(config.getRegistryConfig().getRegistry());
        LocalRegistry.register(Greeter.class.getName(), GreeterImpl.class);
    }

    @AfterClass
    public static void tearDown() {
        if (survivor != null) {
            survivor.stop();
        }
    }

    @Test
    public void callsSurviveAProviderCrashOnlyWithFailOver() throws Exception {
        int crashedPort = freePort();
        int survivorPort = freePort();
        VertxTcpServer crashed = new VertxTcpServer();
        crashed.doStart(crashedPort);
        survivor = new VertxTcpServer();
        survivor.doStart(survivorPort);
        registry.register(node(crashedPort));
        registry.register(node(survivorPort));
        try {
            crashAndCall(crashed);
        } finally {
            registry.unRegister(node(crashedPort));
            registry.unRegister(node(survivorPort));
            survivor.stop();
            survivor = null;
        }
    }

    private void crashAndCall(VertxTcpServer crashed) {

        Greeter greeter = ServiceProxyFactory.getProxy(Greeter.class);
        for (int i = 0; i < 10; i++) {
            assertEquals("hello " + i, greeter.greet(String.valueOf(i)));
        }

        // Crash without unregistering: the registry keeps listing the dead provider until its lease expires
        crashed.stop();

        config.setOutlierEjection(false);
        config.setTolerantStrategy(TolerantStrategyKeys.FAIL_FAST);
        int failures = 0;
        for (int i = 0; i < 10; i++) {
            try {
                greeter.greet("x");
            } catch (RuntimeException e) {
                failures++;
            }
        }
        assertTrue("round robin should hit the dead provider about half the time, failed " + failures,
                failures >= 3);

        config.setTolerantStrategy(TolerantStrategyKeys.FAIL_OVER);
        for (int i = 0; i < 20; i++) {
            assertEquals("hello " + i, greeter.greet(String.valueOf(i)));
        }
    }

    @Test
    public void outlierEjectionStopsRoutingToACrashedProvider() throws Exception {
        int crashedPort = freePort();
        int healthyPort = freePort();
        VertxTcpServer crashed = new VertxTcpServer();
        crashed.doStart(crashedPort);
        VertxTcpServer healthy = new VertxTcpServer();
        healthy.doStart(healthyPort);
        try {
            registry.register(node(crashedPort));
            registry.register(node(healthyPort));
            config.setOutlierEjection(true);
            config.setTolerantStrategy(TolerantStrategyKeys.FAIL_FAST);
            Greeter greeter = ServiceProxyFactory.getProxy(Greeter.class);
            greeter.greet("warm");

            crashed.stop();
            int failures = 0;
            for (int i = 0; i < 50; i++) {
                try {
                    greeter.greet("x");
                } catch (RuntimeException e) {
                    failures++;
                }
            }
            // Three consecutive failures eject it; without ejection about half of the 50 calls would fail
            assertTrue("failed " + failures + " of 50", failures <= 3);
        } finally {
            registry.unRegister(node(crashedPort));
            registry.unRegister(node(healthyPort));
            healthy.stop();
        }
    }

    @Test
    public void serviceExceptionsReachTheCallerWithoutFailOver() throws Exception {
        int port = freePort();
        VertxTcpServer server = new VertxTcpServer();
        server.doStart(port);
        try {
            registry.register(node(port));
            config.setTolerantStrategy(TolerantStrategyKeys.FAIL_OVER);
            Greeter greeter = ServiceProxyFactory.getProxy(Greeter.class);
            try {
                greeter.fail("boom");
                fail("expected the service's exception");
            } catch (IllegalStateException e) {
                assertEquals("boom", e.getMessage());
            }
        } finally {
            registry.unRegister(node(port));
            server.stop();
        }
    }

    private static ServiceMetaInfo node(int port) {
        ServiceMetaInfo info = new ServiceMetaInfo();
        info.setServiceName(Greeter.class.getName());
        info.setServiceHost("127.0.0.1");
        info.setServicePort(port);
        return info;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
