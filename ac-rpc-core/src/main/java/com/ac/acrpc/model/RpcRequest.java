package com.ac.acrpc.model;

import com.ac.acrpc.constant.RpcConstant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RPC request
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RpcRequest implements Serializable {

    /**
     * Service name
     */
    private String serviceName;

    /**
     * Method name
     */
    private String methodName;

    /**
     * Service version
     */
    private String serviceVersion = RpcConstant.DEFAULT_SERVICE_VERSION;

    /**
     * Parameter types
     */
    private Class<?>[] parameterTypes;

    /**
     * Arguments
     */
    private Object[] args;

}
