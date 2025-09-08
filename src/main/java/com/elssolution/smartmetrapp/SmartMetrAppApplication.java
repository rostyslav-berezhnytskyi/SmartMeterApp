package com.elssolution.smartmetrapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SmartMetrAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartMetrAppApplication.class, args);
    }

}
