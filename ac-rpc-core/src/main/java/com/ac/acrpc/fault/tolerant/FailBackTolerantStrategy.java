package com.ac.acrpc.fault.tolerant;

import com.ac.acrpc.model.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Fail-back: degrade to a default value for the method's return type (0, false or null).
 */
@Slf4j
public class FailBackTolerantStrategy implements TolerantStrategy {

    @Override
    public RpcResponse doTolerant(Map<String, Object> context, Exception e) {
        Method method = (Method) context.get(METHOD);
        log.warn("Call to {} failed, returning a default value", method == null ? "?" : method.getName(), e);
        RpcResponse response = new RpcResponse();
        response.setData(method == null ? null : defaultValue(method.getReturnType()));
        response.setMessage("fallback");
        return response;
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }
}
