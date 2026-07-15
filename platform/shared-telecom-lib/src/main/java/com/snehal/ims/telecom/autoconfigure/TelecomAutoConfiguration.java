package com.snehal.ims.telecom.autoconfigure;

import com.snehal.ims.telecom.hep.HepCaptureProperties;
import com.snehal.ims.telecom.hep.HepCaptureService;
import com.snehal.ims.telecom.hep.HepV3Encoder;
import com.snehal.ims.telecom.hep.HepV3Sender;
import com.snehal.ims.telecom.metrics.SipMetrics;
import com.snehal.ims.telecom.sip.SipStackManager;
import com.snehal.ims.telecom.sip.SipStackProperties;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sip.SipListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for {@code shared-telecom-lib}. Every pod that declares the
 * dependency gets HEPv3 capture + shared SIP metrics for free; the Jain SIP stack is
 * wired only for signaling pods ({@code ims.sip.enabled=true}, the default).
 *
 * <p>All beans are {@code @ConditionalOnMissingBean} so a pod can override any of them.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties({SipStackProperties.class, HepCaptureProperties.class})
public class TelecomAutoConfiguration {

    // ---- HEPv3 capture ----

    @Bean
    @ConditionalOnMissingBean
    public HepV3Encoder hepV3Encoder() {
        return new HepV3Encoder();
    }

    @Bean
    @ConditionalOnMissingBean
    public HepV3Sender hepV3Sender(HepCaptureProperties properties) {
        return new HepV3Sender(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public HepCaptureService hepCaptureService(HepV3Encoder encoder,
                                               HepV3Sender sender,
                                               HepCaptureProperties properties,
                                               MeterRegistry meterRegistry) {
        return new HepCaptureService(encoder, sender, properties, meterRegistry);
    }

    // ---- Shared SIP metrics ----

    @Bean
    @ConditionalOnMissingBean
    public SipMetrics sipMetrics(MeterRegistry meterRegistry) {
        return new SipMetrics(meterRegistry);
    }

    // ---- Jain SIP stack (signaling pods only) ----

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ims.sip", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SipStackManager sipStackManager(SipStackProperties properties,
                                           ObjectProvider<SipListener> listenerProvider) {
        return new SipStackManager(properties, listenerProvider);
    }
}
