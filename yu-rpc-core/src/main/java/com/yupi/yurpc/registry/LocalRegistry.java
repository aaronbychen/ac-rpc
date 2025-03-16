package com.yupi.yurpc.registry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local register center
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public class LocalRegistry {

    /**
     * Register info storage
     */
    private static final Map<String, Class<?>> map = new ConcurrentHashMap<>();

    /**
     * Register service
     *
     * @param serviceName
     * @param implClass
     */
    public static void register(String serviceName, Class<?> implClass) {
        map.put(serviceName, implClass);
    }

    /**
     * Get service
     *
     * @param serviceName
     * @return
     */
    public static Class<?> get(String serviceName) {
        return map.get(serviceName);
    }

    /**
     * Delete service
     *
     * @param serviceName
     */
    public static void remove(String serviceName) {
        map.remove(serviceName);
    }
}
