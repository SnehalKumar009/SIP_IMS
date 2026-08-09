package com.snehal.ims.pcscf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PcscfProperties.class)
public class PcscfApplication {

    public static void main(String[] args) {
        SpringApplication.run(PcscfApplication.class, args);
    }
}
