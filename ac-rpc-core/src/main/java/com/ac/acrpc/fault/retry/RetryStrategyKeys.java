package com.ac.acrpc.fault.retry;

/**
 * Retry strategy keys
 */
public interface RetryStrategyKeys {

    /**
     * No retry
     */
    String NO = "no";

    /**
     * Fixed interval
     */
    String FIXED_INTERVAL = "fixedInterval";

}
