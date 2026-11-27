package com.ac.example.consumer;

import com.ac.example.common.model.User;
import com.ac.example.common.service.UserService;
import com.ac.acrpc.bootstrap.ConsumerBootstrap;
import com.ac.acrpc.proxy.ServiceProxyFactory;

/**
 * Service consumer example.
 */
public class ConsumerExample {

    public static void main(String[] args) {
        // Initialize consumer
        ConsumerBootstrap.init();

        // Get proxy
        UserService userService = ServiceProxyFactory.getProxy(UserService.class);
        User user = new User();
        user.setName("ac");
        User newUser = userService.getUser(user);
        if (newUser != null) {
            System.out.println(newUser.getName());
        } else {
            System.out.println("user == null");
        }
    }
}
