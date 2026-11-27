package com.ac.acrpc.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RPC response
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RpcResponse implements Serializable {

    /**
     * Response data
     */
    private Object data;

    /**
     * Response data type (reserved)
     */
    private Class<?> dataType;

    /**
     * Response message
     */
    private String message;

    /**
     * Exception, if any
     */
    private Exception exception;

}
