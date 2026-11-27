package com.ac.acrpc.fault.retry;

import com.ac.acrpc.model.RpcResponse;
import org.junit.Test;

/**
 * Retry strategy test
 */
public class RetryStrategyTest {

    RetryStrategy retryStrategy = new NoRetryStrategy();

    @Test
    public void doRetry() {
        try {
            RpcResponse rpcResponse = retryStrategy.doRetry(() -> {
                System.out.println("Testing retry");
                throw new RuntimeException("Simulated retry failure");
            });
            System.out.println(rpcResponse);
        } catch (Exception e) {
            System.out.println("All retries failed");
            e.printStackTrace();
        }
    }
}