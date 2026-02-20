package com.ac.example.common.model;

import java.io.Serializable;

/**
 * 用户
 *
 * @author <a href="https://github.com/aaronbychen">Aaron Chen</a>
 */
public class User implements Serializable {

    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
