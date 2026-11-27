package com.ac.acrpc.model;

import cn.hutool.core.util.StrUtil;
import com.ac.acrpc.constant.RpcConstant;
import lombok.Data;

/**
 * Service metadata (registry entry)
 */
@Data
public class ServiceMetaInfo {

    /**
     * Service name
     */
    private String serviceName;

    /**
     * Service version
     */
    private String serviceVersion = RpcConstant.DEFAULT_SERVICE_VERSION;

    /**
     * Service host
     */
    private String serviceHost;

    /**
     * Service port
     */
    private Integer servicePort;

    /**
     * Service group (not implemented yet)
     */
    private String serviceGroup = "default";

    /**
     * Returns the service key, e.g. {@code serviceName:version}.
     *
     * @return
     */
    public String getServiceKey() {
        // TODO: include service group once supported
//        return String.format("%s:%s:%s", serviceName, serviceVersion, serviceGroup);
        return String.format("%s:%s", serviceName, serviceVersion);
    }

    /**
     * Returns the registry node key, e.g. {@code serviceName:version/host:port}.
     *
     * @return
     */
    public String getServiceNodeKey() {
        return String.format("%s/%s:%s", getServiceKey(), serviceHost, servicePort);
    }

    /**
     * Returns the full service address, prefixing {@code http://} if missing.
     *
     * @return
     */
    public String getServiceAddress() {
        if (!StrUtil.contains(serviceHost, "http")) {
            return String.format("http://%s:%s", serviceHost, servicePort);
        }
        return String.format("%s:%s", serviceHost, servicePort);
    }
}
