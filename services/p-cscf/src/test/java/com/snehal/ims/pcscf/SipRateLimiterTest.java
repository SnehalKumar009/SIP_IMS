package com.snehal.ims.pcscf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SipRateLimiterTest {

    @Test
    void allowsUpToCapacityThenBlocks() {
        PcscfProperties props = new PcscfProperties();
        props.getRateLimit().setCapacity(3);
        props.getRateLimit().setRefillTokens(3);
        props.getRateLimit().setRefillPeriodSeconds(60);
        SipRateLimiter limiter = new SipRateLimiter(props);

        assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
        assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
        assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
        assertThat(limiter.tryAcquire("10.0.0.1")).isFalse();
    }

    @Test
    void bucketsAreIsolatedPerSource() {
        PcscfProperties props = new PcscfProperties();
        props.getRateLimit().setCapacity(1);
        props.getRateLimit().setRefillTokens(1);
        props.getRateLimit().setRefillPeriodSeconds(60);
        SipRateLimiter limiter = new SipRateLimiter(props);

        assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
        assertThat(limiter.tryAcquire("10.0.0.1")).isFalse();
        assertThat(limiter.tryAcquire("10.0.0.2")).isTrue();
    }

    @Test
    void disabledLimiterAlwaysAllows() {
        PcscfProperties props = new PcscfProperties();
        props.getRateLimit().setEnabled(false);
        props.getRateLimit().setCapacity(1);
        SipRateLimiter limiter = new SipRateLimiter(props);

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
        }
    }
}
