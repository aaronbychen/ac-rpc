package com.ac.acrpc.bench;

import com.ac.acrpc.bootstrap.ConsumerBootstrap;
import com.ac.acrpc.proxy.ServiceProxyFactory;

/**
 * Blocking echo call against the system under test. Both implementations are driven the same
 * way, one virtual thread per request, through each framework's idiomatic blocking API.
 */
interface EchoClient {

    byte[] echo(byte[] payload) throws Exception;

    static EchoClient create(Options options) {
        return switch (Main.system(options)) {
            case "acrpc" -> {
                ConsumerBootstrap.init(Main.rpcConfig(options));
                EchoService proxy = ServiceProxyFactory.getProxy(EchoService.class);
                yield proxy::echo;
            }
            case "grpc" -> new GrpcEchoClient(options.get("host", "localhost"), options.getInt("port", 8080),
                    options.getInt("connections", 1), options.getInt("deadline-ms", 3000));
            default -> throw new IllegalArgumentException("Unknown --system " + Main.system(options));
        };
    }
}
