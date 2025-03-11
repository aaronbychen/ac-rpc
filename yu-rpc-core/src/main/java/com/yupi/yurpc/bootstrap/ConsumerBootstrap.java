package com.yupi.yurpc.bootstrap;

import com.yupi.yurpc.RpcApplication;

/**
 * Service consumer startup class (init)
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public class ConsumerBootstrap {

    /**
     * init
     */
    public static void init() {
        // RPC framework init (configuration and registry)
        RpcApplication.init();
    }

}
