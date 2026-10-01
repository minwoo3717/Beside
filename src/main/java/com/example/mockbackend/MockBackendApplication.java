package com.example.mockbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync(proxyTargetClass = true) // match JobServiceImpl injection with a class-based proxy
public class MockBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(MockBackendApplication.class, args);
    }
}
