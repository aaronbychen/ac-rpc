package com.ac.acrpc.serializer;

import com.ac.acrpc.spi.SpiLoader;

/**
 * Serializer factory
 */
public class SerializerFactory {

    static {
        SpiLoader.load(Serializer.class);
    }

    /**
     * Default serializer
     */
    private static final Serializer DEFAULT_SERIALIZER = new JdkSerializer();

    /**
     * Get serializer instance by key
     *
     * @param key
     * @return
     */
    public static Serializer getInstance(String key) {
        return SpiLoader.getInstance(Serializer.class, key);
    }

}
