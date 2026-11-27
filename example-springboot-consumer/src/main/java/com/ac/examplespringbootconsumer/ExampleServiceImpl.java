package com.ac.examplespringbootconsumer;

import com.ac.example.common.model.User;
import com.ac.example.common.service.UserService;
import com.ac.acrpc.springboot.starter.annotation.RpcReference;
import org.springframework.stereotype.Service;

/**
 * Example service.
 */
@Service
public class ExampleServiceImpl {

    /**
     * Injected by the RPC framework.
     */
    @RpcReference
    private UserService userService;

    /**
     * Test method.
     */
    public void test() {
        User user = new User();
        user.setName("ac");
        User resultUser = userService.getUser(user);
        System.out.println(resultUser.getName());
    }

}
