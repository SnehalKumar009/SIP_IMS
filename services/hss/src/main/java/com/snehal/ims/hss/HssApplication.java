package com.snehal.ims.hss;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(HssProperties.class)
public class HssApplication {

    public static void main(String[] args) {
        SpringApplication.run(HssApplication.class, args);
    }
}
