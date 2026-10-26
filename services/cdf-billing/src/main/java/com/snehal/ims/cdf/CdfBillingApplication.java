package com.snehal.ims.cdf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(CdfProperties.class)
public class CdfBillingApplication {

    public static void main(String[] args) {
        SpringApplication.run(CdfBillingApplication.class, args);
    }
}
