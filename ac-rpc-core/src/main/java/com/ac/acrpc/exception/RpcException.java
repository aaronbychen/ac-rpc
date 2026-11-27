package com.ac.acrpc.exception;

/**
 * Customized exception class
 */
public class RpcException extends RuntimeException {

    public RpcException(String message) {
        super(message);
    }

}
