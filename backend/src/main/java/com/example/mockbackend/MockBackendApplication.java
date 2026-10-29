package com.example.mockbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync(proxyTargetClass = true) // match JobServiceImpl injection with a class-based proxy
@EnableScheduling // InferenceHealthMonitor (real), DatabaseBackup (prod)
public class MockBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(MockBackendApplication.class, args);
    }
}
