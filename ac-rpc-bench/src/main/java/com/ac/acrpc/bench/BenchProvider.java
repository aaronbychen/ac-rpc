package com.ac.acrpc.bench;

import com.ac.acrpc.bootstrap.ProviderBootstrap;
import com.ac.acrpc.model.ServiceRegisterInfo;

import java.util.List;

/**
 * Starts a provider that serves {@link EchoService}.
 */
final class BenchProvider {

    static void run(Options options) {
        ProviderBootstrap.init(Main.rpcConfig(options),
                List.of(new ServiceRegisterInfo<>(EchoService.class.getName(), EchoServiceImpl.class)));
    }
}
