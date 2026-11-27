package com.ac.example.provider;

import com.ac.example.common.model.User;
import com.ac.example.common.service.UserService;

/**
 * User service implementation.
 */
public class UserServiceImpl implements UserService {

    public User getUser(User user) {
        System.out.println("Username: " + user.getName());
        return user;
    }
}
