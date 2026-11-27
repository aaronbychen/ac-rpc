package com.ac.acrpc.fault.tolerant;

import com.ac.acrpc.model.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Fail-over fault-tolerance strategy: retry on another service node
 */
@Slf4j
public class FailOverTolerantStrategy implements TolerantStrategy {

    @Override
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        // TODO: pick another service node and invoke it
        return null;
    }
}
