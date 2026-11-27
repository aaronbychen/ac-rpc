package com.ac.acrpc.fault.tolerant;

import com.ac.acrpc.model.RpcResponse;

import java.util.Map;

/**
 * Fail-fast fault-tolerance strategy: rethrow to the caller immediately
 */
public class FailFastTolerantStrategy implements TolerantStrategy {

    @Override
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        throw new RuntimeException("Service call failed", e);
    }
}
