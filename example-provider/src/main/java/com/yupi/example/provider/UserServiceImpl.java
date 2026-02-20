package com.ac.example.provider;

import com.ac.example.common.model.User;
import com.ac.example.common.service.UserService;

/**
 * 用户服务实现类
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public class UserServiceImpl implements UserService {

    public User getUser(User user) {
        System.out.println("用户名：" + user.getName());
        return user;
    }
}
