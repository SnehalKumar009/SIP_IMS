package com.snehal.ims.scscf;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * S-CSCF configuration bound from {@code ims.scscf.*}, validated at startup so a
 * malformed deployment fails immediately rather than at the first REGISTER.
 *
 * <p>{@code self*} is what this pod advertises in Via, Record-Route and Service-Route;
 * under the StatefulSet model it must be the pod's own stable DNS name, because the
 * HSS stores it as the subscriber's serving S-CSCF and the I-CSCF routes terminating
 * requests straight back to it.</p>
 */
@ConfigurationProperties(prefix = "ims.scscf")
@Validated
public class ScscfProperties {

    /** Address this S-CSCF advertises to peers (its externally reachable host/IP). */
    @NotBlank
    private String selfHost = "127.0.0.1";

    /** Port this S-CSCF advertises (normally equal to {@code ims.sip.port}). */
    @Min(1)
    @Max(65535)
    private int selfPort = 5060;

    /** Home network domain this S-CSCF serves. */
    @NotBlank
    private String homeDomain = "ims.snehal.com";

    /**
     * SIP URI reported to the HSS as this server's name. Blank derives it from
     * {@link #selfHost}/{@link #selfPort}, which is the right answer for a StatefulSet
     * pod whose {@code selfHost} is its stable DNS name.
     */
    private String serverName = "";

    /** Binding store implementation: {@code memory} (per-pod) or {@code redis} (shared). */
    @Pattern(regexp = "memory|redis")
    private String store = "memory";

    @Valid
    private final Registration registration = new Registration();

    @Valid
    private final Auth auth = new Auth();

    @Valid
    private final RateLimit rateLimit = new RateLimit();

    @Valid
    private final Routing routing = new Routing();

    @Valid
    private final Cx cx = new Cx();

    /**
     * Cx boundary tuning. A circuit breaker reacts only after failures accumulate, so it
     * cannot on its own stop slow HSS answers from pinning every Jain SIP listener thread.
     * The deadline bounds each call and the bulkhead bounds how many threads can be in the
     * HSS at once, leaving the stack able to answer while the Cx interface is unhealthy.
     */
    public static class Cx {
        /** Per-call gRPC deadline. */
        private Duration timeout = Duration.ofMillis(750);

        /** Max SIP listener threads allowed inside the HSS concurrently. */
        @Min(1)
        private int maxConcurrentCalls = 16;

        /** How long a thread waits for a bulkhead permit before failing fast. */
        private Duration maxWait = Duration.ofMillis(50);

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
        public void setMaxConcurrentCalls(int maxConcurrentCalls) { this.maxConcurrentCalls = maxConcurrentCalls; }
        public Duration getMaxWait() { return maxWait; }
        public void setMaxWait(Duration maxWait) { this.maxWait = maxWait; }
    }

    public static class Registration {
        /** Expiry granted when the UE asks for none. */
        @Min(60)
        private int defaultExpires = 600;

        /** Shortest registration accepted; shorter requests are answered with 423. */
        @Min(10)
        private int minExpires = 60;

        /** Longest registration granted; longer requests are clamped down to this. */
        @Min(60)
        private int maxExpires = 3600;

        /** Cap on concurrent bindings per AoR; the soonest-expiring one is evicted past it. */
        @Min(1)
        @Max(64)
        private int maxBindingsPerAor = 5;

        /** How often expired bindings are swept and de-registered towards the HSS. */
        private Duration reaperInterval = Duration.ofSeconds(30);

        public int getDefaultExpires() { return defaultExpires; }
        public void setDefaultExpires(int defaultExpires) { this.defaultExpires = defaultExpires; }
        public int getMinExpires() { return minExpires; }
        public void setMinExpires(int minExpires) { this.minExpires = minExpires; }
        public int getMaxExpires() { return maxExpires; }
        public void setMaxExpires(int maxExpires) { this.maxExpires = maxExpires; }
        public int getMaxBindingsPerAor() { return maxBindingsPerAor; }
        public void setMaxBindingsPerAor(int maxBindingsPerAor) { this.maxBindingsPerAor = maxBindingsPerAor; }
        public Duration getReaperInterval() { return reaperInterval; }
        public void setReaperInterval(Duration reaperInterval) { this.reaperInterval = reaperInterval; }
    }

    public static class Auth {
        /**
         * Digest algorithm. {@code MD5} is 3GPP SIP Digest and the interop default;
         * {@code SHA-256} is RFC 8760 and requires a UE that supports it.
         */
        @Pattern(regexp = "MD5|SHA-256")
        private String algorithm = "MD5";

        /** How long an issued nonce stays usable. */
        private Duration nonceLifetime = Duration.ofMinutes(5);

        /** Cap on outstanding challenges, bounding memory under a REGISTER flood. */
        @Min(100)
        private int maxOutstandingNonces = 50_000;

        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
        public Duration getNonceLifetime() { return nonceLifetime; }
        public void setNonceLifetime(Duration nonceLifetime) { this.nonceLifetime = nonceLifetime; }
        public int getMaxOutstandingNonces() { return maxOutstandingNonces; }
        public void setMaxOutstandingNonces(int maxOutstandingNonces) { this.maxOutstandingNonces = maxOutstandingNonces; }
    }

    /**
     * Per-identity REGISTER limiter. The P-CSCF limits by source IP, which does not stop
     * one authenticated subscriber from hammering the registrar and the Cx interface.
     */
    public static class RateLimit {
        private boolean enabled = true;

        @Min(1)
        private long capacity = 10;

        @Min(1)
        private long refillTokens = 10;

        @Min(1)
        private long refillPeriodSeconds = 60;

        /** Cap on tracked identities, bounding memory when the source set is hostile. */
        @Min(100)
        private long maxTrackedIdentities = 100_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getCapacity() { return capacity; }
        public void setCapacity(long capacity) { this.capacity = capacity; }
        public long getRefillTokens() { return refillTokens; }
        public void setRefillTokens(long refillTokens) { this.refillTokens = refillTokens; }
        public long getRefillPeriodSeconds() { return refillPeriodSeconds; }
        public void setRefillPeriodSeconds(long refillPeriodSeconds) { this.refillPeriodSeconds = refillPeriodSeconds; }
        public long getMaxTrackedIdentities() { return maxTrackedIdentities; }
        public void setMaxTrackedIdentities(long maxTrackedIdentities) { this.maxTrackedIdentities = maxTrackedIdentities; }
    }

    public static class Routing {
        /**
         * How many times a request may legitimately traverse this proxy (IMS spirals
         * through the S-CSCF on originating and again on terminating). Beyond this the
         * request is a loop and gets 482.
         */
        @Min(1)
        @Max(10)
        private int maxSpirals = 2;

        /** Max-Forwards applied when a request arrives without the header. */
        @Min(1)
        @Max(70)
        private int defaultMaxForwards = 70;

        /** Retry-After advertised while the pod is draining for shutdown. */
        @Min(1)
        private int drainingRetryAfterSeconds = 30;

        public int getMaxSpirals() { return maxSpirals; }
        public void setMaxSpirals(int maxSpirals) { this.maxSpirals = maxSpirals; }
        public int getDefaultMaxForwards() { return defaultMaxForwards; }
        public void setDefaultMaxForwards(int defaultMaxForwards) { this.defaultMaxForwards = defaultMaxForwards; }
        public int getDrainingRetryAfterSeconds() { return drainingRetryAfterSeconds; }
        public void setDrainingRetryAfterSeconds(int drainingRetryAfterSeconds) {
            this.drainingRetryAfterSeconds = drainingRetryAfterSeconds;
        }
    }

    /** SIP URI this pod reports to the HSS in SAR and advertises in Service-Route. */
    public String resolvedServerName() {
        if (serverName != null && !serverName.isBlank()) {
            return serverName;
        }
        return "sip:" + selfHost + ":" + selfPort;
    }

    public String getSelfHost() { return selfHost; }
    public void setSelfHost(String selfHost) { this.selfHost = selfHost; }
    public int getSelfPort() { return selfPort; }
    public void setSelfPort(int selfPort) { this.selfPort = selfPort; }
    public String getHomeDomain() { return homeDomain; }
    public void setHomeDomain(String homeDomain) { this.homeDomain = homeDomain; }
    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public String getStore() { return store; }
    public void setStore(String store) { this.store = store; }
    public Registration getRegistration() { return registration; }
    public Auth getAuth() { return auth; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Routing getRouting() { return routing; }
    public Cx getCx() { return cx; }
}
