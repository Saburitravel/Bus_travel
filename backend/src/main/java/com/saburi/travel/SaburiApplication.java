package com.saburi.travel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication
@EnableMongoRepositories(considerNestedRepositories = true)
public class SaburiApplication {
    public static void main(String[] args) {
        SpringApplication.run(SaburiApplication.class, args);
    }
}
