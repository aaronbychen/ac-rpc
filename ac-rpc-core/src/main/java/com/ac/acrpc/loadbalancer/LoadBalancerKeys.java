package com.ac.acrpc.loadbalancer;

/**
 * 负载均衡器键名常量
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public interface LoadBalancerKeys {

    /**
     * 轮询
     */
    String ROUND_ROBIN = "roundRobin";

    String RANDOM = "random";

    String CONSISTENT_HASH = "consistentHash";

}
