package com.github.wuzhudexiaopacai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CohererApplication {

    public static void main(String[] args) {
        SpringApplication.run(CohererApplication.class, args);
    }
}
