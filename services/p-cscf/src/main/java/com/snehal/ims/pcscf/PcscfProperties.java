package com.snehal.ims.pcscf;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P-CSCF specific configuration bound from {@code ims.pcscf.*}.
 *
 * <p>{@code self*} values are what this proxy advertises in Via / Record-Route / Path
 * (must be reachable by the next hop and by the UE); {@code nextHop*} is the upstream
 * I-CSCF. Until Phase 3 the next hop is a static stub target.</p>
 */
@ConfigurationProperties(prefix = "ims.pcscf")
public class PcscfProperties {

    /** Address this P-CSCF advertises to peers (its externally reachable host/IP). */
    private String selfHost = "127.0.0.1";

    /** Port this P-CSCF advertises (normally equal to {@code ims.sip.port}). */
    private int selfPort = 5060;

    /** Upstream I-CSCF (static stub until Phase 3). */
    private final NextHop nextHop = new NextHop();

    /** Per-source token-bucket rate limiting. */
    private final RateLimit rateLimit = new RateLimit();

    public static class NextHop {
        private String host = "i-cscf.ims-core.svc.cluster.local";
        private int port = 5060;
        private String transport = "udp";

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }
    }

    public static class RateLimit {
        private boolean enabled = true;
        /** Bucket size (burst) allowed per source IP. */
        private long capacity = 50;
        /** Tokens refilled per {@link #refillPeriodSeconds}. */
        private long refillTokens = 50;
        /** Refill window in seconds. */
        private long refillPeriodSeconds = 1;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getCapacity() { return capacity; }
        public void setCapacity(long capacity) { this.capacity = capacity; }
        public long getRefillTokens() { return refillTokens; }
        public void setRefillTokens(long refillTokens) { this.refillTokens = refillTokens; }
        public long getRefillPeriodSeconds() { return refillPeriodSeconds; }
        public void setRefillPeriodSeconds(long refillPeriodSeconds) { this.refillPeriodSeconds = refillPeriodSeconds; }
    }

    public String getSelfHost() { return selfHost; }
    public void setSelfHost(String selfHost) { this.selfHost = selfHost; }
    public int getSelfPort() { return selfPort; }
    public void setSelfPort(int selfPort) { this.selfPort = selfPort; }
    public NextHop getNextHop() { return nextHop; }
    public RateLimit getRateLimit() { return rateLimit; }
}
