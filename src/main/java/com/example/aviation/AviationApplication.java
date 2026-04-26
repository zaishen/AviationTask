package com.example.aviation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.example.aviation.config.AviationApiProperties;

@SpringBootApplication
@EnableConfigurationProperties(AviationApiProperties.class)
public class AviationApplication {

    public static void main(String[] args) {
        SpringApplication.run(AviationApplication.class, args);
    }
}
