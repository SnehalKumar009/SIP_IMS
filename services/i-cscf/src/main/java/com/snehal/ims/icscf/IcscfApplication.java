package com.snehal.ims.icscf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(IcscfProperties.class)
public class IcscfApplication {

    public static void main(String[] args) {
        SpringApplication.run(IcscfApplication.class, args);
    }
}
