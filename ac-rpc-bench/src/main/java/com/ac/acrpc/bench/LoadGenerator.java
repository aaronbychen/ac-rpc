package com.ac.acrpc.bench;

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop load generator.
 *
 * <p>Requests are issued on a fixed schedule (one every 1/rate seconds) regardless of whether
 * earlier requests have completed, and each latency is measured from the request's
 * <em>intended</em> start time rather than the moment it was actually sent. A closed-loop
 * generator (N threads, each waiting for its previous response) slows down exactly when the
 * system under test does, so it never records the queueing delay real clients would see;
 * this is known as coordinated omission and systematically understates tail latency.
 *
 * <p>Latencies of every completed request are recorded, including ones slower than the
 * timeout. Requests still outstanding after the drain period are reported as incomplete;
 * when they exceed 0.1% of measured requests, the percentiles are flagged as unreliable.
 *
 * <p>A monitor samples this JVM's thread count and memory. If the thread count passes
 * {@code --max-threads}, or the run overruns its schedule, the run is aborted and whatever was
 * measured so far is reported, so a leaking client cannot take the whole machine down.
 */
final class LoadGenerator {

    private static final long SPIN_THRESHOLD_NS = TimeUnit.MILLISECONDS.toNanos(2);

    private final EchoClient service;
    private final byte[] payload;
    private final Recorder recorder = new Recorder(3);

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger measuredInFlight = new AtomicInteger();
    private final LongAdder ok = new LongAdder();
    private final LongAdder slow = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final long timeoutNs;
    private volatile String firstError;
    private volatile String abortReason;
    private volatile long abortAtNs;
    private volatile int peakThreads;
    private volatile long peakCommittedMb;

    private LoadGenerator(EchoClient service, byte[] payload, long timeoutNs) {
        this.service = service;
        this.payload = payload;
        this.timeoutNs = timeoutNs;
    }

    static void run(Options options) throws Exception {
        double rate = options.getDouble("rate", 100);
        double warmupSec = options.getDouble("warmup", 10);
        double durationSec = options.getDouble("duration", 30);
        double timeoutSec = options.getDouble("timeout", 5);
        int payloadBytes = options.getInt("payload", 64);
        int maxInFlight = options.getInt("max-in-flight", 10_000);
        int maxThreads = options.getInt("max-threads", 2_000);
        Path out = Path.of(options.require("out"));

        EchoClient service = EchoClient.create(options);

        byte[] payload = new byte[payloadBytes];
        new Random(42).nextBytes(payload);
        byte[] reply = service.echo(payload);
        if (reply == null || reply.length != payloadBytes) {
            throw new IllegalStateException("Sanity call returned an unexpected reply");
        }

        LoadGenerator generator = new LoadGenerator(service, payload, secondsToNanos(timeoutSec));
        Map<String, Object> result = generator.drive(rate, warmupSec, durationSec, timeoutSec, maxInFlight, maxThreads);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("rate", rate);
        config.put("warmupSec", warmupSec);
        config.put("durationSec", durationSec);
        config.put("timeoutSec", timeoutSec);
        config.put("payloadBytes", payloadBytes);
        config.put("maxInFlight", maxInFlight);
        config.put("maxThreads", maxThreads);
        config.put("system", Main.system(options));
        config.put("serializer", options.get("serializer", "kryo"));
        config.put("connections", options.get("connections", "default"));
        config.put("transport", options.get("transport", "vertx"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("labels", options.withPrefix("label."));
        report.put("config", config);
        report.put("environment", environment());
        report.putAll(result);

        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, JSONUtil.toJsonPrettyStr(JSONUtil.parseObj(report, JSONConfig.create().setIgnoreNullValue(false))),
                StandardCharsets.UTF_8);
        System.out.println(summaryLine(result));
        // The client under test may leave non-daemon threads behind
        System.exit(0);
    }

    private Map<String, Object> drive(double rate, double warmupSec, double durationSec, double timeoutSec,
                                      int maxInFlight, int maxThreads) throws InterruptedException {
        long total = (long) Math.floor(rate * (warmupSec + durationSec));
        long start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        long measureStart = start + secondsToNanos(warmupSec);
        long measureEnd = start + secondsToNanos(warmupSec + durationSec);
        long issuedMeasured = 0;
        long maxLagNs = 0;
        long hardDeadline = measureEnd + secondsToNanos(timeoutSec) + TimeUnit.SECONDS.toNanos(30);
        Thread monitor = startMonitor(maxThreads, hardDeadline);

        // Not closed with try-with-resources: close() would wait forever on requests the client never completes
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (long i = 0; i < total && abortReason == null; i++) {
                long intended = start + (long) (i * 1e9 / rate);
                long now;
                while ((now = System.nanoTime()) < intended) {
                    long remaining = intended - now;
                    if (remaining > SPIN_THRESHOLD_NS) {
                        LockSupport.parkNanos(remaining - SPIN_THRESHOLD_NS);
                    } else {
                        Thread.onSpinWait();
                    }
                }
                boolean measured = intended >= measureStart;
                if (measured) {
                    issuedMeasured++;
                    maxLagNs = Math.max(maxLagNs, now - intended);
                }
                if (inFlight.get() >= maxInFlight) {
                    if (measured) {
                        dropped.increment();
                    }
                    continue;
                }
                inFlight.incrementAndGet();
                if (measured) {
                    measuredInFlight.incrementAndGet();
                }
                executor.execute(() -> call(intended, measured));
            }

            // Drain: give outstanding requests up to one timeout to finish
            long drainDeadline = System.nanoTime() + secondsToNanos(timeoutSec);
            while (abortReason == null && measuredInFlight.get() > 0 && System.nanoTime() < drainDeadline) {
                Thread.sleep(10);
            }
            Histogram histogram = recorder.getIntervalHistogram();
            int incomplete = measuredInFlight.get();
            // On abort, only the part of the window that actually ran counts toward throughput
            long windowEnd = abortReason == null ? measureEnd : Math.max(measureStart, Math.min(measureEnd, abortAtNs));
            Map<String, Object> result = buildResult(histogram, issuedMeasured, incomplete,
                    Math.max(1e-9, (windowEnd - measureStart) / 1e9), maxLagNs);
            if (abortReason != null) {
                result.put("abortedAfterSec", round((abortAtNs - start) / 1e9));
            }
            return result;
        } finally {
            monitor.interrupt();
            executor.shutdownNow();
        }
    }

    private Thread startMonitor(int maxThreads, long hardDeadline) {
        var threads = ManagementFactory.getThreadMXBean();
        Thread monitor = Thread.ofPlatform().daemon().name("bench-monitor").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                int count = threads.getThreadCount();
                peakThreads = Math.max(peakThreads, count);
                peakCommittedMb = Math.max(peakCommittedMb, ProcessMemory.committedMb());
                if (abortReason == null) {
                    if (count > maxThreads) {
                        abort("thread count " + count + " exceeded --max-threads " + maxThreads);
                    } else if (System.nanoTime() > hardDeadline) {
                        abort("run overran its schedule");
                    }
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        return monitor;
    }

    private void abort(String reason) {
        abortAtNs = System.nanoTime();
        abortReason = reason;
    }

    private void call(long intended, boolean measured) {
        try {
            byte[] reply = service.echo(payload);
            long latency = System.nanoTime() - intended;
            if (!measured) {
                return;
            }
            if (reply == null || reply.length != payload.length) {
                recordError("unexpected reply");
                return;
            }
            recorder.recordValue(latency);
            if (latency > timeoutNs) {
                slow.increment();
            } else {
                ok.increment();
            }
        } catch (Throwable t) {
            if (measured) {
                recordError(t.toString());
            }
        } finally {
            inFlight.decrementAndGet();
            if (measured) {
                measuredInFlight.decrementAndGet();
            }
        }
    }

    private void recordError(String message) {
        errors.increment();
        if (firstError == null) {
            firstError = message;
        }
    }

    private Map<String, Object> buildResult(Histogram h, long issued, int incomplete, double windowSec, long maxLagNs) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("issued", issued);
        counts.put("ok", ok.sum());
        counts.put("slow", slow.sum());
        counts.put("errors", errors.sum());
        counts.put("dropped", dropped.sum());
        counts.put("incomplete", incomplete);

        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("count", h.getTotalCount());
        if (h.getTotalCount() > 0) {
            latency.put("p50", ms(h.getValueAtPercentile(50)));
            latency.put("p90", ms(h.getValueAtPercentile(90)));
            latency.put("p99", ms(h.getValueAtPercentile(99)));
            latency.put("p999", ms(h.getValueAtPercentile(99.9)));
            latency.put("max", ms(h.getMaxValue()));
            latency.put("mean", round(h.getMean() / 1e6));
        }

        long failed = errors.sum() + dropped.sum() + incomplete;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", counts);
        result.put("latencyMs", latency);
        result.put("throughputOkPerSec", round(ok.sum() / windowSec));
        result.put("errorRate", issued == 0 ? 0 : round((double) failed / issued));
        result.put("percentilesReliable", abortReason == null && issued > 0
                && (double) (incomplete + dropped.sum()) / issued <= 0.001);
        result.put("aborted", abortReason != null);
        result.put("abortReason", abortReason);
        result.put("peakThreads", peakThreads);
        result.put("peakCommittedMb", peakCommittedMb);
        result.put("maxSchedulerLagMs", ms(maxLagNs));
        result.put("firstError", firstError);
        result.put("histogram", encode(h));
        return result;
    }

    private static String summaryLine(Map<String, Object> result) {
        return "result " + JSONUtil.toJsonStr(Map.of(
                "counts", result.get("counts"),
                "latencyMs", result.get("latencyMs"),
                "throughputOkPerSec", result.get("throughputOkPerSec"),
                "peakThreads", result.get("peakThreads"),
                "aborted", result.get("aborted")));
    }

    private static Map<String, Object> environment() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("timestamp", Instant.now().toString());
        env.put("javaVersion", System.getProperty("java.version"));
        env.put("javaVendor", System.getProperty("java.vendor"));
        env.put("vmName", System.getProperty("java.vm.name"));
        env.put("osName", System.getProperty("os.name"));
        env.put("osArch", System.getProperty("os.arch"));
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        env.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).toList());
        env.put("jvmArgs", List.copyOf(runtime.getInputArguments()));
        return env;
    }

    /** Compressed, base64-encoded histogram so runs can be merged later without loss. */
    private static String encode(Histogram h) {
        ByteBuffer buffer = ByteBuffer.allocate(h.getNeededByteBufferCapacity());
        int length = h.encodeIntoCompressedByteBuffer(buffer);
        byte[] bytes = new byte[length];
        buffer.flip();
        buffer.get(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static long secondsToNanos(double seconds) {
        return (long) (seconds * 1e9);
    }

    private static double ms(long nanos) {
        return round(nanos / 1e6);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
