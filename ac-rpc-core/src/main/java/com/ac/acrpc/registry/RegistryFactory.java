package com.ac.acrpc.registry;

import com.ac.acrpc.spi.SpiLoader;

/**
 * Registry factory
 */
public class RegistryFactory {

    // Load implementations via SPI
    static {
        SpiLoader.load(Registry.class);
    }

    /**
     * Default registry
     */
    private static final Registry DEFAULT_REGISTRY = new EtcdRegistry();

    /**
     * Get registry instance by key
     *
     * @param key
     * @return
     */
    public static Registry getInstance(String key) {
        return SpiLoader.getInstance(Registry.class, key);
    }

}
