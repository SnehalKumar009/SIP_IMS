package com.snehal.ims.scscf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Serving-CSCF: the registrar and session controller of the IMS core.
 *
 * <p>Authenticates REGISTER with SIP Digest against credentials pulled from the HSS
 * (Cx MAR), owns the subscriber binding store, reports its assignment back to the HSS
 * (Cx SAR), and routes originating and terminating requests for the users it serves.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(ScscfProperties.class)
@EnableScheduling
public class ScscfApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScscfApplication.class, args);
    }
}
