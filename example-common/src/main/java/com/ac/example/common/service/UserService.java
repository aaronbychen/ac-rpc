package com.ac.example.common.service;

import com.ac.example.common.model.User;

/**
 * 用户服务
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public interface UserService {

    /**
     * 获取用户
     *
     * @param user
     * @return
     */
    User getUser(User user);

    /**
     * 用于测试 mock 接口返回值
     *
     * @return
     */
    default short getNumber() {
        return 1;
    }
}
