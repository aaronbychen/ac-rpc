package com.ac.acrpc.fault;

import com.ac.acrpc.RpcApplication;
import com.ac.acrpc.config.RpcConfig;
import com.ac.acrpc.model.ServiceMetaInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Consumer-side outlier ejection.
 *
 * <p>A provider is ejected after {@code consecutiveFailures} transport failures in a row
 * (connect errors, closed connections, deadlines); exceptions thrown by the service itself do
 * not count. While ejected it is left out of load balancing. Each ejection lasts twice as long
 * as the previous one, up to {@code maxEjectionMs}; once it expires the provider is back in
 * rotation and is ejected again quickly if it still fails. A success resets both counters.
 *
 * <p>This covers the gap between a provider dying and the registry noticing, which with etcd
 * leases is up to one TTL. If every candidate is ejected, all of them are returned, since
 * failing over to nothing is worse than trying a provider that might have recovered.
 */
@Slf4j
public class OutlierDetector {

    private final int consecutiveFailures;
    private final long baseEjectionNs;
    private final long maxEjectionNs;
    private final LongSupplier clock;
    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();

    private static volatile OutlierDetector shared;

    private static final class State {
        int failures;
        int ejections;
        boolean ejected;
        long ejectedUntilNs;
    }

    /**
     * Process-wide detector built from the global config, or null when ejection is disabled.
     */
    public static OutlierDetector shared() {
        if (shared == null) {
            synchronized (OutlierDetector.class) {
                if (shared == null) {
                    RpcConfig config = RpcApplication.getRpcConfig();
                    shared = new OutlierDetector(config.getOutlierConsecutiveFailures(),
                            config.getOutlierBaseEjectionMs(), config.getOutlierMaxEjectionMs());
                }
            }
        }
        return RpcApplication.getRpcConfig().isOutlierEjection() ? shared : null;
    }

    public OutlierDetector(int consecutiveFailures, long baseEjectionMs, long maxEjectionMs) {
        this(consecutiveFailures, baseEjectionMs, maxEjectionMs, System::nanoTime);
    }

    OutlierDetector(int consecutiveFailures, long baseEjectionMs, long maxEjectionMs, LongSupplier clock) {
        this.consecutiveFailures = consecutiveFailures;
        this.baseEjectionNs = TimeUnit.MILLISECONDS.toNanos(baseEjectionMs);
        this.maxEjectionNs = TimeUnit.MILLISECONDS.toNanos(maxEjectionMs);
        this.clock = clock;
    }

    /**
     * The candidates that are not currently ejected, or all of them if every one is.
     */
    public List<ServiceMetaInfo> available(List<ServiceMetaInfo> candidates) {
        long now = clock.getAsLong();
        List<ServiceMetaInfo> healthy = new ArrayList<>(candidates.size());
        for (ServiceMetaInfo node : candidates) {
            State state = states.get(node.getServiceNodeKey());
            if (state == null || !isEjected(state, now)) {
                healthy.add(node);
            }
        }
        return healthy.isEmpty() ? candidates : healthy;
    }

    public void onSuccess(ServiceMetaInfo node) {
        State state = states.get(node.getServiceNodeKey());
        if (state != null) {
            synchronized (state) {
                state.failures = 0;
                state.ejections = 0;
                state.ejected = false;
            }
        }
    }

    public void onFailure(ServiceMetaInfo node) {
        State state = states.computeIfAbsent(node.getServiceNodeKey(), k -> new State());
        long now = clock.getAsLong();
        synchronized (state) {
            if (isEjected(state, now)) {
                return; // calls that were in flight when it was ejected do not extend the ejection
            }
            state.failures++;
            if (state.failures >= consecutiveFailures) {
                long duration = Math.min(maxEjectionNs, baseEjectionNs << Math.min(state.ejections, 20));
                state.ejected = true;
                state.ejectedUntilNs = now + duration;
                state.ejections++;
                state.failures = 0;
                log.warn("Ejected {} for {} ms after {} consecutive failures", node.getServiceNodeKey(),
                        TimeUnit.NANOSECONDS.toMillis(duration), consecutiveFailures);
            }
        }
    }

    private static boolean isEjected(State state, long now) {
        synchronized (state) {
            return state.ejected && now - state.ejectedUntilNs < 0;
        }
    }
}
