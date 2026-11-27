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
     * Class name of the exception the service threw, or null on success. Exceptions are sent as
     * type and message, not as objects: serializers such as Kryo cannot read Throwable's private
     * fields under the JDK module system, and the caller may not have the class anyway.
     */
    private String errorType;

}
