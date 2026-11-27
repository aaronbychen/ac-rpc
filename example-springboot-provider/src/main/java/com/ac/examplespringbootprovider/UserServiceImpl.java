package com.ac.examplespringbootprovider;

import com.ac.example.common.model.User;
import com.ac.example.common.service.UserService;
import com.ac.acrpc.springboot.starter.annotation.RpcService;
import org.springframework.stereotype.Service;

/**
 * User service implementation.
 */
@Service
@RpcService
public class UserServiceImpl implements UserService {

    public User getUser(User user) {
        System.out.println("Username: " + user.getName());
        return user;
    }
}
