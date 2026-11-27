package com.ac.example.provider;

import com.ac.example.common.service.UserService;
import com.ac.acrpc.bootstrap.ProviderBootstrap;
import com.ac.acrpc.model.ServiceRegisterInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Service provider example.
 */
public class ProviderExample {

    public static void main(String[] args) {
        // Services to register
        List<ServiceRegisterInfo<?>> serviceRegisterInfoList = new ArrayList<>();
        ServiceRegisterInfo<UserService> serviceRegisterInfo = new ServiceRegisterInfo<>(UserService.class.getName(), UserServiceImpl.class);
        serviceRegisterInfoList.add(serviceRegisterInfo);

        // Initialize provider
        ProviderBootstrap.init(serviceRegisterInfoList);
    }
}
