package com.ac.acrpc.springboot.starter.annotation;

import com.ac.acrpc.constant.RpcConstant;
import com.ac.acrpc.fault.retry.RetryStrategyKeys;
import com.ac.acrpc.fault.tolerant.TolerantStrategyKeys;
import com.ac.acrpc.loadbalancer.LoadBalancerKeys;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field to be injected with an RPC service proxy.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RpcReference {

    /**
     * Service interface.
     */
    Class<?> interfaceClass() default void.class;

    /**
     * Service version.
     */
    String serviceVersion() default RpcConstant.DEFAULT_SERVICE_VERSION;

    /**
     * Load balancer.
     */
    String loadBalancer() default LoadBalancerKeys.ROUND_ROBIN;

    /**
     * Retry strategy.
     */
    String retryStrategy() default RetryStrategyKeys.NO;

    /**
     * Fault tolerance strategy.
     */
    String tolerantStrategy() default TolerantStrategyKeys.FAIL_FAST;

    /**
     * Whether to return mock results.
     */
    boolean mock() default false;

}
