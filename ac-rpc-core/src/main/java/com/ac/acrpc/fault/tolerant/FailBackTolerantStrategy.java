package com.ac.acrpc.fault.tolerant;

import com.ac.acrpc.model.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Fail-back fault-tolerance strategy: degrade to a fallback service
 */
@Slf4j
public class FailBackTolerantStrategy implements TolerantStrategy {

    @Override
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        // TODO: look up and invoke a fallback service
        return null;
    }
}
