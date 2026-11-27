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

    /**
     * Per-call deadline in milliseconds
     */
    private long requestTimeoutMs = 3000;

    /**
     * TCP connect timeout in milliseconds
     */
    private int connectTimeoutMs = 3000;

    /**
     * Pooled connections per provider endpoint; requests are multiplexed over each one
     */
    private int connectionsPerEndpoint = 2;

    /**
     * In-flight request cap per connection; calls beyond it fail fast instead of queueing
     */
    private int maxPendingPerConnection = 10_000;

    /**
     * Client transport: "vertx" (event loop) or "blocking" (blocking sockets on virtual threads)
     */
    private String transport = "vertx";

    /**
     * Skip providers that keep failing at the transport level until they recover
     */
    private boolean outlierEjection = true;

    /**
     * Consecutive transport failures that eject a provider
     */
    private int outlierConsecutiveFailures = 3;

    /**
     * First ejection length; each further ejection doubles it
     */
    private long outlierBaseEjectionMs = 1000;

    /**
     * Longest ejection
     */
    private long outlierMaxEjectionMs = 30_000;
}
