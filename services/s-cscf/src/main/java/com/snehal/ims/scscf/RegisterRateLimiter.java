package com.snehal.ims.scscf;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Token-bucket limiter on REGISTER, keyed by public identity.
 *
 * <p>The P-CSCF already limits by source IP, which does not stop a single subscriber —
 * or a botnet spreading one identity across many addresses — from driving the registrar
 * and, through it, the Cx interface and the HSS database.</p>
 */
@Component
public class RegisterRateLimiter {

    private final ScscfProperties.RateLimit config;
    private final Cache<String, Bucket> buckets;

    public RegisterRateLimiter(ScscfProperties properties) {
        this.config = properties.getRateLimit();
        this.buckets = Caffeine.newBuilder()
                .maximumSize(config.getMaxTrackedIdentities())
                .expireAfterAccess(Duration.ofSeconds(Math.max(60, config.getRefillPeriodSeconds() * 2)))
                .build();
    }

    /** @return {@code true} when a REGISTER for this identity may proceed. */
    public boolean tryAcquire(String identity) {
        if (!config.isEnabled()) {
            return true;
        }
        String key = identity == null ? "" : identity;
        return buckets.get(key, k -> newBucket()).tryConsume(1);
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(config.getCapacity())
                .refillGreedy(config.getRefillTokens(), Duration.ofSeconds(config.getRefillPeriodSeconds()))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
