package com.ac.acrpc.exception;

import lombok.Getter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * An exception thrown by the remote service whose original type cannot be rebuilt locally.
 */
@Getter
public class RemoteServiceException extends RpcException {

    /**
     * Class name of the exception on the provider
     */
    private final String remoteType;

    public RemoteServiceException(String remoteType, String message) {
        super(remoteType + ": " + message);
        this.remoteType = remoteType;
    }

    /**
     * Rebuilds the provider's exception when its class is available here, has a String
     * constructor, and may be thrown from {@code method} (unchecked, or declared); otherwise
     * returns a {@link RemoteServiceException}.
     */
    public static Throwable rebuild(String type, String message, Method method) {
        try {
            Class<?> cls = Class.forName(type, false, method.getDeclaringClass().getClassLoader());
            if (Throwable.class.isAssignableFrom(cls) && throwableFrom(cls, method)) {
                Constructor<?> constructor = cls.getConstructor(String.class);
                return (Throwable) constructor.newInstance(message);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // fall through to the generic exception
        }
        return new RemoteServiceException(type, message);
    }

    private static boolean throwableFrom(Class<?> cls, Method method) {
        if (RuntimeException.class.isAssignableFrom(cls) || Error.class.isAssignableFrom(cls)) {
            return true;
        }
        for (Class<?> declared : method.getExceptionTypes()) {
            if (declared.isAssignableFrom(cls)) {
                return true;
            }
        }
        return false;
    }
}
