package com.ac.acrpc.fault.retry;

import com.ac.acrpc.model.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;

/**
 * No retry - retry strategy
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
@Slf4j
public class NoRetryStrategy implements RetryStrategy {

    /**
     * Retry
     *
     * @param callable
     * @return
     * @throws Exception
     */
    public RpcResponse doRetry(Callable<RpcResponse> callable) throws Exception {
        return callable.call();
    }

}
