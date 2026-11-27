package com.ac.acrpc.bootstrap;

import com.ac.acrpc.RpcApplication;
import com.ac.acrpc.config.RpcConfig;

/**
 * Service consumer startup class (init)
 */
public class ConsumerBootstrap {

    /**
     * init
     */
    public static void init() {
        // RPC framework init (configuration and registry)
        RpcApplication.init();
    }

    /**
     * Init with an explicit config
     */
    public static void init(RpcConfig rpcConfig) {
        RpcApplication.init(rpcConfig);
    }
}
