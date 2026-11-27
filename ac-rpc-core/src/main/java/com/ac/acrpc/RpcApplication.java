package com.ac.acrpc;

import com.ac.acrpc.config.RegistryConfig;
import com.ac.acrpc.config.RpcConfig;
import com.ac.acrpc.constant.RpcConstant;
import com.ac.acrpc.registry.Registry;
import com.ac.acrpc.registry.RegistryFactory;
import com.ac.acrpc.utils.ConfigUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC framework application
 * holder that holds global constants, implemented by double-checked Locking
 */
@Slf4j
public class RpcApplication {

    private static volatile RpcConfig rpcConfig;

    /**
     * Initializes the framework with a custom config.
     *
     * @param newRpcConfig
     */
    public static void init(RpcConfig newRpcConfig) {
        rpcConfig = newRpcConfig;
        log.info("rpc init, config = {}", newRpcConfig.toString());
        // Initialize the registry
        RegistryConfig registryConfig = rpcConfig.getRegistryConfig();
        Registry registry = RegistryFactory.getInstance(registryConfig.getRegistry());
        registry.init(registryConfig);
        log.info("registry init, config = {}", registryConfig);
        // Destroy the registry on JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(registry::destroy));
    }

    /**
     * Initializes the framework with config loaded from application.properties.
     */
    public static void init() {
        RpcConfig newRpcConfig;
        try {
            newRpcConfig = ConfigUtils.loadConfig(RpcConfig.class, RpcConstant.DEFAULT_CONFIG_PREFIX);
        } catch (Exception e) {
            // Fall back to defaults if config loading fails
            newRpcConfig = new RpcConfig();
        }
        init(newRpcConfig);
    }


    /**
     * Returns the global config, lazily initializing it on first access.
     *
     * @return
     */
    public static RpcConfig getRpcConfig() {
        if (rpcConfig == null) {
            synchronized (RpcApplication.class) {
                if (rpcConfig == null) {
                    init();
                }
            }
        }
        return rpcConfig;
    }
}
