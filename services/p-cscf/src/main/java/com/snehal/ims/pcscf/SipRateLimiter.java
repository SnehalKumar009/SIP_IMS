package com.snehal.ims.pcscf;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-source-IP token-bucket rate limiter guarding the SIP entry point against
 * registration / INVITE floods. One {@link Bucket} is kept per source address.
 */
@Component
public class SipRateLimiter {

    private final PcscfProperties.RateLimit config;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public SipRateLimiter(PcscfProperties properties) {
        this.config = properties.getRateLimit();
    }

    /** @return true if the request from {@code sourceIp} is allowed to proceed. */
    public boolean tryAcquire(String sourceIp) {
        if (!config.isEnabled()) {
            return true;
        }
        return bucketFor(sourceIp).tryConsume(1);
    }

    private Bucket bucketFor(String sourceIp) {
        return buckets.computeIfAbsent(sourceIp, ip -> {
            Bandwidth limit = Bandwidth.builder()
                    .capacity(config.getCapacity())
                    .refillGreedy(config.getRefillTokens(),
                            Duration.ofSeconds(config.getRefillPeriodSeconds()))
                    .build();
            return Bucket.builder().addLimit(limit).build();
        });
    }
}
