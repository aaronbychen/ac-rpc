package com.ac.acrpc.proxy;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Mock service proxy (JDK dynamic proxy)
 */
@Slf4j
public class MockServiceProxy implements InvocationHandler {

    /**
     * Invoke the proxy
     *
     * @return
     * @throws Throwable
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Return a default value based on the method's return type
        Class<?> methodReturnType = method.getReturnType();
        log.info("mock invoke {}", method.getName());
        return getDefaultObject(methodReturnType);
    }

    /**
     * Create a default value for the given type (extend as needed)
     *
     * @param type
     * @return
     */
    private Object getDefaultObject(Class<?> type) {
        // Primitives
        if (type.isPrimitive()) {
            if (type == boolean.class) {
                return false;
            } else if (type == short.class) {
                return (short) 0;
            } else if (type == int.class) {
                return 0;
            } else if (type == long.class) {
                return 0L;
            }
        }
        // Objects
        return null;
    }
}
