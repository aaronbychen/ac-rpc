import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal check for a lost wakeup when one virtual thread blocks reading and another blocks
 * writing on the same socket. No RPC code involved.
 *
 * Server: a platform thread per connection echoes bytes back.
 * Client: a virtual-thread writer sends continuously; a virtual-thread reader drains replies.
 * With the server echoing, the client writer blocks only while the reader is behind, so both
 * should keep making progress. A stall is reported when neither counter moves for 5 seconds.
 *
 * Usage: java PollerStall.java [virtual|platform] [seconds]
 */
public class PollerStall {

    public static void main(String[] args) throws Exception {
        boolean virtual = !"platform".equals(args.length > 0 ? args[0] : "virtual");
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 20;

        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().start(() -> {
            try (Socket s = server.accept()) {
                s.setTcpNoDelay(true);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (Exception ignored) {
                // client closed
            }
        });

        Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        socket.setTcpNoDelay(true);
        AtomicLong written = new AtomicLong();
        AtomicLong read = new AtomicLong();
        Thread.Builder builder = virtual ? Thread.ofVirtual() : Thread.ofPlatform().daemon();

        builder.start(() -> {
            try {
                OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
                byte[] frame = new byte[64];
                while (true) {
                    for (int i = 0; i < 256; i++) { // a batch, like a coalescing writer
                        out.write(frame);
                    }
                    out.flush();
                    written.addAndGet(256L * frame.length);
                }
            } catch (Exception e) {
                System.out.println("writer stopped: " + e);
            }
        });
        builder.start(() -> {
            try {
                InputStream in = socket.getInputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    read.addAndGet(n);
                }
            } catch (Exception e) {
                System.out.println("reader stopped: " + e);
            }
        });

        long lastW = -1, lastR = -1, stuckSince = 0;
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(500);
            long w = written.get(), r = read.get();
            if (w == lastW && r == lastR) {
                if (stuckSince == 0) {
                    stuckSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - stuckSince >= 5000) {
                    System.out.printf("STALL %s java=%s written=%d read=%d%n",
                            virtual ? "virtual" : "platform", System.getProperty("java.version"), w, r);
                    System.exit(1);
                }
            } else {
                stuckSince = 0;
            }
            lastW = w;
            lastR = r;
        }
        System.out.printf("OK %s java=%s written=%dMB read=%dMB%n", virtual ? "virtual" : "platform",
                System.getProperty("java.version"), written.get() >> 20, read.get() >> 20);
        System.exit(0);
    }
}
