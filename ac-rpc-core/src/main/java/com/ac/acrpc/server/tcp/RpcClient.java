package com.ac.acrpc.server.tcp;

import com.ac.acrpc.model.RpcRequest;
import com.ac.acrpc.model.RpcResponse;
import com.ac.acrpc.model.ServiceMetaInfo;

/**
 * Client-side transport: sends one request and blocks the calling thread until the matching
 * response arrives, the deadline passes, or the connection fails.
 */
public interface RpcClient extends AutoCloseable {

    RpcResponse call(RpcRequest request, ServiceMetaInfo target, String serializer, long timeoutMs) throws Exception;

    @Override
    void close();
}
