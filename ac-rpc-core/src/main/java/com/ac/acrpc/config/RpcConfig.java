package com.ac.acrpc.config;

import com.ac.acrpc.fault.retry.RetryStrategyKeys;
import com.ac.acrpc.fault.tolerant.TolerantStrategyKeys;
import com.ac.acrpc.loadbalancer.LoadBalancerKeys;
import com.ac.acrpc.serializer.SerializerKeys;
import lombok.Data;

/**
 * Global RPC framework config
 */
@Data
public class RpcConfig {

    /**
     * Name
     */
    private String name = "ac-rpc";

    /**
     * Version
     */
    private String version = "1.0";

    /**
     * Server host
     */
    private String serverHost = "localhost";

    /**
     * Server port
     */
    private Integer serverPort = 8080;

    /**
     * Serializer
     */
    private String serializer = SerializerKeys.JDK;

    /**
     * Load balancer
     */
    private String loadBalancer = LoadBalancerKeys.ROUND_ROBIN;

    /**
     * Retry strategy
     */
    private String retryStrategy = RetryStrategyKeys.NO;

    /**
     * Fault-tolerance strategy
     */
    private String tolerantStrategy = TolerantStrategyKeys.FAIL_FAST;

    /**
     * Mock calls
     */
    private boolean mock = false;

    /**
     * Registry config
     */
    private RegistryConfig registryConfig = new RegistryConfig();
}
