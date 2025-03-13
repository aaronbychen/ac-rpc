package com.yupi.yurpc.exception;

/**
 * Customized exception class
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public class RpcException extends RuntimeException {

    public RpcException(String message) {
        super(message);
    }

}
