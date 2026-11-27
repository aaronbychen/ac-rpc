package com.ac.examplespringbootconsumer;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;

/**
 * End-to-end example: needs etcd and a running example-springboot-provider.
 */
@SpringBootTest
@Disabled("needs etcd and a running example-springboot-provider")
class ExampleServiceImplTest {

    @Resource
    private ExampleServiceImpl exampleService;

    @Test
    void test1() {
        exampleService.test();
    }
}