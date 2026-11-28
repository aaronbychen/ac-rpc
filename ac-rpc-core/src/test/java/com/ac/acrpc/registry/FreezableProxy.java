package com.ac.acrpc.registry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TCP proxy for tests that can stop forwarding without closing connections, so the peer looks
 * unresponsive (like a hung or partitioned server) rather than refusing connections.
 */
final class FreezableProxy implements AutoCloseable {

    private final ServerSocket server;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private boolean frozen;

    FreezableProxy(String targetHost, int targetPort) throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("proxy-accept").start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    Socket upstream = new Socket(targetHost, targetPort);
                    sockets.add(client);
                    sockets.add(upstream);
                    pump(client, upstream);
                    pump(upstream, client);
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    int port() {
        return server.getLocalPort();
    }

    void freeze() {
        synchronized (lock) {
            frozen = true;
        }
    }

    void thaw() {
        synchronized (lock) {
            frozen = false;
            lock.notifyAll();
        }
    }

    private void pump(Socket from, Socket to) {
        Thread.ofPlatform().daemon().name("proxy-pump").start(() -> {
            byte[] buffer = new byte[16 * 1024];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    synchronized (lock) {
                        while (frozen) {
                            lock.wait();
                        }
                    }
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (IOException | InterruptedException e) {
                // connection closed
            }
        });
    }

    @Override
    public void close() throws IOException {
        thaw();
        server.close();
        for (Socket socket : sockets) {
            socket.close();
        }
    }
}
