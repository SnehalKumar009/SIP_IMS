package com.snehal.ims.scscf;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * S-CSCF specific Micrometer instruments, layered on top of the platform-wide
 * {@code ims_sip_*} meters from shared-telecom-lib.
 */
@Component
public class ScscfMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> registrationsByType = new ConcurrentHashMap<>();
    private final Counter challenges;
    private final Counter authFailures;
    private final Counter rateLimited;
    private final Counter loopsDetected;
    private final Counter bindingsExpired;

    public ScscfMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.challenges = Counter.builder("ims_scscf_auth_challenges_total")
                .description("Digest challenges issued in a 401 response")
                .register(registry);
        this.authFailures = Counter.builder("ims_scscf_auth_failures_total")
                .description("REGISTER attempts rejected with a bad digest response")
                .register(registry);
        this.rateLimited = Counter.builder("ims_scscf_register_rate_limited_total")
                .description("REGISTER requests rejected by the per-identity limiter")
                .register(registry);
        this.loopsDetected = Counter.builder("ims_scscf_loops_detected_total")
                .description("Requests rejected with 482 after exceeding the spiral limit")
                .register(registry);
        this.bindingsExpired = Counter.builder("ims_scscf_bindings_expired_total")
                .description("Bindings reaped after their registration lapsed")
                .register(registry);
    }

    /** @param type one of {@code register}, {@code refresh}, {@code deregister} */
    public void recordRegistration(String type) {
        registrationsByType.computeIfAbsent(type, t ->
                Counter.builder("ims_scscf_registrations_total")
                        .tag("type", t)
                        .description("Completed registration operations by kind")
                        .register(registry)).increment();
    }

    public void recordChallenge() {
        challenges.increment();
    }

    public void recordAuthFailure() {
        authFailures.increment();
    }

    public void recordRateLimited() {
        rateLimited.increment();
    }

    public void recordLoopDetected() {
        loopsDetected.increment();
    }

    public void recordBindingsExpired(int count) {
        bindingsExpired.increment(count);
    }
}
