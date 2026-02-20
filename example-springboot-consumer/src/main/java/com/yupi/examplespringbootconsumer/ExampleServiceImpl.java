package com.ac.examplespringbootconsumer;

import com.ac.example.common.model.User;
import com.ac.example.common.service.UserService;
import com.ac.acrpc.springboot.starter.annotation.RpcReference;
import org.springframework.stereotype.Service;

/**
 * 示例服务实现类
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
@Service
public class ExampleServiceImpl {

    /**
     * 使用 Rpc 框架注入
     */
    @RpcReference
    private UserService userService;

    /**
     * 测试方法
     */
    public void test() {
        User user = new User();
        user.setName("ac");
        User resultUser = userService.getUser(user);
        System.out.println(resultUser.getName());
    }

}
