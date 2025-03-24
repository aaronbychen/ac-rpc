package com.ac.acrpc.fault.retry;

/**
 * 重试策略键名常量
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public interface RetryStrategyKeys {

    /**
     * 不重试
     */
    String NO = "no";

    /**
     * 固定时间间隔
     */
    String FIXED_INTERVAL = "fixedInterval";

}
