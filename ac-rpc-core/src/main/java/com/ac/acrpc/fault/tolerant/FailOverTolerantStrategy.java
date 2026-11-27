package com.ac.acrpc.fault.tolerant;

import com.ac.acrpc.model.RpcResponse;
import com.ac.acrpc.model.ServiceMetaInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Fail-over: try each other provider once, in random order, and return the first success.
 *
 * <p>Only transport failures reach this strategy; an exception thrown by the service itself is
 * returned to the caller without trying other providers. A request that reached the failed
 * provider may still have run there, so this is only safe for idempotent methods.
 */
@Slf4j
public class FailOverTolerantStrategy implements TolerantStrategy {

    @Override
    @SuppressWarnings("unchecked")
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        ServiceMetaInfo failed = (ServiceMetaInfo) context.get(FAILED_NODE);
        List<ServiceMetaInfo> candidates = (List<ServiceMetaInfo>) context.get(CANDIDATES);
        Invoker invoker = (Invoker) context.get(INVOKER);
        if (candidates == null || invoker == null) {
            throw new RuntimeException("Service call failed", e);
        }

        List<ServiceMetaInfo> others = new ArrayList<>();
        for (ServiceMetaInfo node : candidates) {
            if (failed == null || !node.getServiceNodeKey().equals(failed.getServiceNodeKey())) {
                others.add(node);
            }
        }
        // Spread the failed provider's traffic across the rest instead of piling it onto one
        Collections.shuffle(others);

        Exception last = e;
        for (ServiceMetaInfo node : others) {
            try {
                RpcResponse response = invoker.call(node);
                log.info("Failed over from {} to {}", failed == null ? "?" : failed.getServiceNodeKey(),
                        node.getServiceNodeKey());
                return response;
            } catch (Exception next) {
                next.addSuppressed(last);
                last = next;
            }
        }
        throw new RuntimeException("Service call failed on all " + (others.size() + 1) + " provider(s)", last);
    }
}
