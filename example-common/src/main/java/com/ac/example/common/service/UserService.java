package com.ac.example.common.service;

import com.ac.example.common.model.User;

/**
 * User service.
 */
public interface UserService {

    /**
     * Gets a user.
     *
     * @param user
     * @return
     */
    User getUser(User user);

    /**
     * Used to test mock return values.
     *
     * @return
     */
    default short getNumber() {
        return 1;
    }
}
