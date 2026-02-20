package com.ac.example.consumer;

import com.ac.example.common.model.User;
import com.ac.example.common.service.UserService;
import com.ac.acrpc.bootstrap.ConsumerBootstrap;
import com.ac.acrpc.proxy.ServiceProxyFactory;

/**
 * 服务消费者示例
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public class ConsumerExample {

    public static void main(String[] args) {
        // 服务提供者初始化
        ConsumerBootstrap.init();

        // 获取代理
        UserService userService = ServiceProxyFactory.getProxy(UserService.class);
        User user = new User();
        user.setName("ac");
        // 调用
        User newUser = userService.getUser(user);
        if (newUser != null) {
            System.out.println(newUser.getName());
        } else {
            System.out.println("user == null");
        }
    }
}
