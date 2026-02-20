package com.ac.acrpc.config;

import com.ac.acrpc.fault.retry.RetryStrategyKeys;
import com.ac.acrpc.fault.tolerant.TolerantStrategyKeys;
import com.ac.acrpc.loadbalancer.LoadBalancerKeys;
import com.ac.acrpc.serializer.SerializerKeys;
import lombok.Data;

/**
 * RPC 框架全局配置
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
@Data
public class RpcConfig {

    /**
     * 名称
     */
    private String name = "ac-rpc";

    /**
     * 版本号
     */
    private String version = "1.0";

    /**
     * 服务器主机名
     */
    private String serverHost = "localhost";

    /**
     * 服务器端口号
     */
    private Integer serverPort = 8080;

    /**
     * 序列化器
     */
    private String serializer = SerializerKeys.JDK;

    /**
     * 负载均衡器
     */
    private String loadBalancer = LoadBalancerKeys.ROUND_ROBIN;

    /**
     * 重试策略
     */
    private String retryStrategy = RetryStrategyKeys.NO;

    /**
     * 容错策略
     */
    private String tolerantStrategy = TolerantStrategyKeys.FAIL_FAST;

    /**
     * 模拟调用
     */
    private boolean mock = false;

    /**
     * 注册中心配置
     */
    private RegistryConfig registryConfig = new RegistryConfig();
}
