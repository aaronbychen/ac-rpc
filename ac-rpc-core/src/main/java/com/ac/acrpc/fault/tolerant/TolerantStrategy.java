package com.ac.acrpc.fault.tolerant;

import com.ac.acrpc.model.RpcResponse;

import java.util.Map;

/**
 * Fault-tolerance strategy
 */
public interface TolerantStrategy {

    /**
     * Handles a failed call.
     *
     * @param context context data for the handler
     * @param e       the exception that occurred
     * @return
     */
    RpcResponse doTolerant(Map<String, Object> context, Exception e);
}
