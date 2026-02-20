package com.ac.acrpc.config;

import com.ac.acrpc.registry.RegistryKeys;
import lombok.Data;

/**
 * RPC framework registrtion center config
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
@Data
public class RegistryConfig {

    /**
     * Registrtion center type
     */
    private String registry = RegistryKeys.ETCD;

    /**
     * Registrtion center address
     */
    private String address = "http://localhost:2380";

    /**
     * Username
     */
    private String username;

    /**
     * Password
     */
    private String password;

    /**
     * Timeout（ms）
     */
    private Long timeout = 10000L;
}
