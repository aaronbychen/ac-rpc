package com.ac.acrpc.config;

import com.ac.acrpc.registry.RegistryKeys;
import lombok.Data;

/**
 * RPC framework registrtion center config
 */
@Data
public class RegistryConfig {

    /**
     * Registry type
     */
    private String registry = RegistryKeys.ETCD;

    /**
     * Registry address
     */
    private String address = "http://localhost:2379";

    /**
     * Username
     */
    private String username;

    /**
     * Password
     */
    private String password;

    /**
     * Timeout (ms)
     */
    private Long timeout = 10000L;

    /**
     * Lease TTL (seconds) for etcd registrations; keys expire this long after a provider dies
     */
    private long leaseTtlSeconds = 30;
}
