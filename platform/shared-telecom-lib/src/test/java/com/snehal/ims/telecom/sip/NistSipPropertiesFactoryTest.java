package com.snehal.ims.telecom.sip;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class NistSipPropertiesFactoryTest {

    @Test
    void appliesEnterpriseTuningDefaults() {
        SipStackProperties props = new SipStackProperties();
        props.setStackName("p-cscf-stack");

        Properties p = NistSipPropertiesFactory.build(props);

        assertThat(p.getProperty("javax.sip.STACK_NAME")).isEqualTo("p-cscf-stack");
        assertThat(p.getProperty("gov.nist.javax.sip.THREAD_POOL_SIZE")).isEqualTo("64");
        assertThat(p.getProperty("gov.nist.javax.sip.REENTRANT_LISTENER")).isEqualTo("true");
        assertThat(p.getProperty("gov.nist.javax.sip.MESSAGE_PROCESSOR_FACTORY"))
                .isEqualTo("gov.nist.javax.sip.stack.NioMessageProcessorFactory");
        assertThat(p.getProperty("javax.sip.AUTOMATIC_DIALOG_SUPPORT")).isEqualTo("off");
    }

    @Test
    void honoursOverriddenThreadPoolSize() {
        SipStackProperties props = new SipStackProperties();
        props.getNist().setThreadPoolSize(128);

        Properties p = NistSipPropertiesFactory.build(props);

        assertThat(p.getProperty("gov.nist.javax.sip.THREAD_POOL_SIZE")).isEqualTo("128");
    }
}
