package com.ac.acrpc.fault.tolerant;

import com.ac.acrpc.model.RpcResponse;
import com.ac.acrpc.model.ServiceMetaInfo;

import java.util.Map;

/**
 * Fault-tolerance strategy, applied after a call (and its retries) failed.
 */
public interface TolerantStrategy {

    /**
     * {@link ServiceMetaInfo}: the provider that failed
     */
    String FAILED_NODE = "failedNode";

    /**
     * {@code List<ServiceMetaInfo>}: all providers known for the service
     */
    String CANDIDATES = "candidates";

    /**
     * {@link Invoker}: calls the same request on a given provider
     */
    String INVOKER = "invoker";

    /**
     * {@link java.lang.reflect.Method}: the interface method being called
     */
    String METHOD = "method";

    /**
     * Sends the original request to one provider.
     */
    @FunctionalInterface
    interface Invoker {
        RpcResponse call(ServiceMetaInfo node) throws Exception;
    }

    /**
     * Handles a failed call.
     *
     * @param context values under the keys defined on this interface
     * @param e       the exception that occurred
     * @return the response to return to the caller
     */
    RpcResponse doTolerant(Map<String, Object> context, Exception e);
}
