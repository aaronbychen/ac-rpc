package com.ac.acrpc.springboot.starter.annotation;

import com.ac.acrpc.springboot.starter.bootstrap.RpcConsumerBootstrap;
import com.ac.acrpc.springboot.starter.bootstrap.RpcInitBootstrap;
import com.ac.acrpc.springboot.starter.bootstrap.RpcProviderBootstrap;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables the RPC framework.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Import({RpcInitBootstrap.class, RpcProviderBootstrap.class, RpcConsumerBootstrap.class})
public @interface EnableRpc {

    /**
     * Whether to start the server.
     *
     * @return
     */
    boolean needServer() default true;
}
