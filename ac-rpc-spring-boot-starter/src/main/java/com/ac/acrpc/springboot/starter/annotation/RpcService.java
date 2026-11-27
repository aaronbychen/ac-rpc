package com.ac.acrpc.springboot.starter.annotation;

import com.ac.acrpc.constant.RpcConstant;
import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an RPC service provider to be registered.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface RpcService {

    /**
     * Service interface.
     */
    Class<?> interfaceClass() default void.class;

    /**
     * Service version.
     */
    String serviceVersion() default RpcConstant.DEFAULT_SERVICE_VERSION;
}
