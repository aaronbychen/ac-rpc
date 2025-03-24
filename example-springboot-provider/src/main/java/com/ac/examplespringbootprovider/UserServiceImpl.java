package com.ac.examplespringbootprovider;

import com.ac.example.common.model.User;
import com.ac.example.common.service.UserService;
import com.ac.acrpc.springboot.starter.annotation.RpcService;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现类
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
@Service
@RpcService
public class UserServiceImpl implements UserService {

    public User getUser(User user) {
        System.out.println("用户名：" + user.getName());
        return user;
    }
}
