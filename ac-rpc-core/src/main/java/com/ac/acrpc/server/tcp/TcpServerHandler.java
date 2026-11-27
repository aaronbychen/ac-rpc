package com.ac.acrpc.server.tcp;

import com.ac.acrpc.model.RpcRequest;
import com.ac.acrpc.model.RpcResponse;
import com.ac.acrpc.protocol.ProtocolMessage;
import com.ac.acrpc.protocol.ProtocolMessageDecoder;
import com.ac.acrpc.protocol.ProtocolMessageEncoder;
import com.ac.acrpc.protocol.ProtocolMessageStatusEnum;
import com.ac.acrpc.protocol.ProtocolMessageTypeEnum;
import com.ac.acrpc.registry.LocalRegistry;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles requests on one server connection.
 *
 * <p>Frames are decoded on the event loop; the service method then runs on a virtual thread so
 * blocking business code never stalls the event loop. Service instances and resolved methods
 * are cached instead of being looked up and instantiated per request.
 */
@Slf4j
public class TcpServerHandler implements Handler<NetSocket> {

    private static final ExecutorService DISPATCHER = Executors.newVirtualThreadPerTaskExecutor();
    private static final Map<Class<?>, Object> INSTANCES = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    @Override
    public void handle(NetSocket socket) {
        socket.handler(new TcpBufferHandlerWrapper(buffer -> {
            ProtocolMessage<RpcRequest> request;
            try {
                @SuppressWarnings("unchecked")
                ProtocolMessage<RpcRequest> decoded = (ProtocolMessage<RpcRequest>) ProtocolMessageDecoder.decode(buffer);
                request = decoded;
            } catch (IOException | RuntimeException e) {
                log.warn("Closing connection from {}: undecodable frame ({})", socket.remoteAddress(), e.toString());
                socket.close();
                return;
            }
            DISPATCHER.execute(() -> respond(socket, request));
        }));
    }

    private void respond(NetSocket socket, ProtocolMessage<RpcRequest> request) {
        RpcResponse rpcResponse = invoke(request.getBody());
        ProtocolMessage.Header header = request.getHeader();
        header.setType((byte) ProtocolMessageTypeEnum.RESPONSE.getKey());
        header.setStatus((byte) ProtocolMessageStatusEnum.OK.getValue());
        Buffer frame;
        try {
            frame = ProtocolMessageEncoder.encode(new ProtocolMessage<>(header, rpcResponse));
        } catch (IOException | RuntimeException e) {
            // Usually a result the serializer cannot handle; tell the caller instead of letting it time out
            log.error("Failed to encode response for request {}", header.getRequestId(), e);
            try {
                frame = ProtocolMessageEncoder.encode(new ProtocolMessage<>(header,
                        error(new IllegalStateException("Response could not be serialized: " + e))));
            } catch (IOException | RuntimeException again) {
                log.error("Failed to encode error response for request {}", header.getRequestId(), again);
                return;
            }
        }
        try {
            socket.write(frame);
        } catch (RuntimeException e) {
            // The connection or server closed while the call ran; the caller's deadline covers it
            log.debug("Dropped response for request {}: {}", header.getRequestId(), e.toString());
        }
    }

    private static RpcResponse error(Throwable t) {
        RpcResponse response = new RpcResponse();
        response.setErrorType(t.getClass().getName());
        response.setMessage(t.getMessage());
        return response;
    }

    private static RpcResponse invoke(RpcRequest rpcRequest) {
        try {
            RpcResponse rpcResponse = new RpcResponse();
            Class<?> implClass = LocalRegistry.get(rpcRequest.getServiceName());
            if (implClass == null) {
                throw new IllegalStateException("No provider registered for " + rpcRequest.getServiceName());
            }
            Method method = METHODS.computeIfAbsent(methodKey(rpcRequest), k -> resolve(implClass, rpcRequest));
            Object instance = INSTANCES.computeIfAbsent(implClass, TcpServerHandler::instantiate);
            Object result = method.invoke(instance, rpcRequest.getArgs());
            rpcResponse.setData(result);
            rpcResponse.setDataType(method.getReturnType());
            rpcResponse.setMessage("ok");
            return rpcResponse;
        } catch (InvocationTargetException e) {
            return error(e.getCause());
        } catch (Exception e) {
            log.warn("Failed to invoke {}.{}: {}", rpcRequest.getServiceName(), rpcRequest.getMethodName(), e.toString());
            return error(e);
        }
    }

    private static String methodKey(RpcRequest r) {
        return r.getServiceName() + "#" + r.getMethodName() + Arrays.toString(r.getParameterTypes());
    }

    private static Method resolve(Class<?> implClass, RpcRequest r) {
        try {
            return implClass.getMethod(r.getMethodName(), r.getParameterTypes());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No method " + methodKey(r), e);
        }
    }

    private static Object instantiate(Class<?> implClass) {
        try {
            return implClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate " + implClass.getName(), e);
        }
    }
}
