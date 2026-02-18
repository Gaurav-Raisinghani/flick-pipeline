package com.flik.autoscaler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AutoscalerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoscalerApplication.class, args);
    }
}
