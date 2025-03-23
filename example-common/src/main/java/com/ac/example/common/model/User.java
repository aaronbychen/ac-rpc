package main.java.com.ac.example.common.model;

import java.io.Serializable;

/**
 * User
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
