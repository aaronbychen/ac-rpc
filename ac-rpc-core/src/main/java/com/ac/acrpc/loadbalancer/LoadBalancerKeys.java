package com.ac.acrpc.loadbalancer;

/**
 * Load balancer keys
 */
public interface LoadBalancerKeys {

    /**
     * Round robin
     */
    String ROUND_ROBIN = "roundRobin";

    String RANDOM = "random";

    String CONSISTENT_HASH = "consistentHash";

}
