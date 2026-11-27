package com.ac.acrpc.proxy;

import cn.hutool.core.collection.CollUtil;
import com.ac.acrpc.RpcApplication;
import com.ac.acrpc.config.RpcConfig;
import com.ac.acrpc.constant.RpcConstant;
import com.ac.acrpc.exception.RemoteServiceException;
import com.ac.acrpc.fault.retry.RetryStrategy;
import com.ac.acrpc.fault.retry.RetryStrategyFactory;
import com.ac.acrpc.fault.tolerant.TolerantStrategy;
import com.ac.acrpc.fault.tolerant.TolerantStrategyFactory;
import com.ac.acrpc.loadbalancer.LoadBalancer;
import com.ac.acrpc.loadbalancer.LoadBalancerFactory;
import com.ac.acrpc.model.RpcRequest;
import com.ac.acrpc.model.RpcResponse;
import com.ac.acrpc.model.ServiceMetaInfo;
import com.ac.acrpc.registry.Registry;
import com.ac.acrpc.registry.RegistryFactory;
import com.ac.acrpc.server.tcp.RpcClients;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service proxy (JDK dynamic proxy)
 */
public class ServiceProxy implements InvocationHandler {

    /**
     * Invoke the proxy
     *
     * @return
     * @throws Throwable
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Build request
        String serviceName = method.getDeclaringClass().getName();
        RpcRequest rpcRequest = RpcRequest.builder()
                .serviceName(serviceName)
                .methodName(method.getName())
                .parameterTypes(method.getParameterTypes())
                .args(args)
                .build();

        // Look up provider addresses from the registry
        RpcConfig rpcConfig = RpcApplication.getRpcConfig();
        Registry registry = RegistryFactory.getInstance(rpcConfig.getRegistryConfig().getRegistry());
        ServiceMetaInfo serviceMetaInfo = new ServiceMetaInfo();
        serviceMetaInfo.setServiceName(serviceName);
        serviceMetaInfo.setServiceVersion(RpcConstant.DEFAULT_SERVICE_VERSION);
        List<ServiceMetaInfo> serviceMetaInfoList = registry.serviceDiscovery(serviceMetaInfo.getServiceKey());
        if (CollUtil.isEmpty(serviceMetaInfoList)) {
            throw new RuntimeException("No service address available");
        }

        // Load balancing
        LoadBalancer loadBalancer = LoadBalancerFactory.getInstance(rpcConfig.getLoadBalancer());
        // Use the method name (request path) as the load balancing key
        Map<String, Object> requestParams = new HashMap<>();
        requestParams.put("methodName", rpcRequest.getMethodName());
        ServiceMetaInfo selectedServiceMetaInfo = loadBalancer.select(requestParams, serviceMetaInfoList);

        // Send the request with retries; on failure, apply the fault-tolerance strategy
        RetryStrategy retryStrategy = RetryStrategyFactory.getInstance(rpcConfig.getRetryStrategy());
        TolerantStrategy.Invoker invoker = node -> retryStrategy.doRetry(() ->
                RpcClients.shared().call(rpcRequest, node, rpcConfig.getSerializer(), rpcConfig.getRequestTimeoutMs()));
        RpcResponse rpcResponse;
        try {
            rpcResponse = invoker.call(selectedServiceMetaInfo);
        } catch (Exception e) {
            Map<String, Object> context = new HashMap<>();
            context.put(TolerantStrategy.FAILED_NODE, selectedServiceMetaInfo);
            context.put(TolerantStrategy.CANDIDATES, serviceMetaInfoList);
            context.put(TolerantStrategy.INVOKER, invoker);
            context.put(TolerantStrategy.METHOD, method);
            TolerantStrategy tolerantStrategy = TolerantStrategyFactory.getInstance(rpcConfig.getTolerantStrategy());
            rpcResponse = tolerantStrategy.doTolerant(context, e);
        }
        // The service itself threw: surface that to the caller, it is not a transport failure
        if (rpcResponse.getErrorType() != null) {
            throw RemoteServiceException.rebuild(rpcResponse.getErrorType(), rpcResponse.getMessage(), method);
        }
        return rpcResponse.getData();
    }
}
