package com.ac.acrpc.fault.tolerant;

/**
 * Fault-tolerance strategy keys
 */
public interface TolerantStrategyKeys {

    /**
     * Fail-back
     */
    String FAIL_BACK = "failBack";

    /**
     * Fail-fast
     */
    String FAIL_FAST = "failFast";

    /**
     * Fail-over
     */
    String FAIL_OVER = "failOver";

    /**
     * Fail-safe
     */
    String FAIL_SAFE = "failSafe";

}
