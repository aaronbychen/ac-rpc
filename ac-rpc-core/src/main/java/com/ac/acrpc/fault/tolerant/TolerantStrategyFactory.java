package com.ac.acrpc.fault.tolerant;

import com.ac.acrpc.spi.SpiLoader;

/**
 * Fault-tolerance strategy factory
 */
public class TolerantStrategyFactory {

    static {
        SpiLoader.load(TolerantStrategy.class);
    }

    /**
     * Default fault-tolerance strategy
     */
    private static final TolerantStrategy DEFAULT_RETRY_STRATEGY = new FailFastTolerantStrategy();

    public static TolerantStrategy getInstance(String key) {
        return SpiLoader.getInstance(TolerantStrategy.class, key);
    }

}
