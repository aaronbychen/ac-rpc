package com.yupi.yurpc.fault.retry;

import com.yupi.yurpc.model.RpcResponse;

import java.util.concurrent.Callable;

/**
 * Retry strategy
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public interface RetryStrategy {

    /**
     * Retry
     *
     * @param callable
     * @return
     * @throws Exception
     */
    RpcResponse doRetry(Callable<RpcResponse> callable) throws Exception;
}
