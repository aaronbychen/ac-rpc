package com.ac.acrpc.bench;

import com.ac.acrpc.config.RegistryConfig;
import com.ac.acrpc.config.RpcConfig;

public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: java -jar ac-rpc-bench.jar <provider|loadgen> [--system acrpc|grpc] [--key value ...]");
            System.exit(2);
        }
        Options options = new Options(args, 1);
        switch (args[0]) {
            case "provider" -> BenchProvider.run(options);
            case "loadgen" -> LoadGenerator.run(options);
            default -> {
                System.err.println("Unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }

    static String system(Options options) {
        return options.get("system", "acrpc");
    }

    static RpcConfig rpcConfig(Options options) {
        RpcConfig config = new RpcConfig();
        config.setServerHost(options.get("host", "localhost"));
        config.setServerPort(options.getInt("port", 8080));
        config.setSerializer(options.get("serializer", "kryo"));
        config.setConnectionsPerEndpoint(options.getInt("connections", config.getConnectionsPerEndpoint()));
        config.setRequestTimeoutMs(options.getInt("deadline-ms", (int) config.getRequestTimeoutMs()));
        config.setTransport(options.get("transport", config.getTransport()));
        config.setTolerantStrategy(options.get("tolerant", config.getTolerantStrategy()));
        config.setRetryStrategy(options.get("retry", config.getRetryStrategy()));
        config.setConnectTimeoutMs(options.getInt("connect-timeout-ms", config.getConnectTimeoutMs()));
        config.setOutlierEjection(Boolean.parseBoolean(options.get("outlier-ejection", String.valueOf(config.isOutlierEjection()))));
        RegistryConfig registry = new RegistryConfig();
        registry.setAddress(options.get("registry", "http://localhost:2379"));
        registry.setLeaseTtlSeconds(options.getInt("lease-ttl", (int) registry.getLeaseTtlSeconds()));
        config.setRegistryConfig(registry);
        return config;
    }
}
