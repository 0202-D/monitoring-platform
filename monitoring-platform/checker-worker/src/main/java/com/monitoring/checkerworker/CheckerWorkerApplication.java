package com.monitoring.checkerworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CheckerWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckerWorkerApplication.class, args);
    }
}
