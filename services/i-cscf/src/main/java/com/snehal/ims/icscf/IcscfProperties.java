package com.snehal.ims.icscf;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * I-CSCF specific configuration bound from {@code ims.icscf.*}.
 *
 * <p>{@code self*} values are what this proxy advertises in Via / Record-Route (must be
 * reachable by both the downstream P-CSCF and the upstream S-CSCF). {@link #homeDomain}
 * and {@link #visitedNetwork} populate the Cx User-Authorization request. {@link #scscf}
 * is the default S-CSCF the I-CSCF selects when the HSS returns only a capability set.</p>
 */
@ConfigurationProperties(prefix = "ims.icscf")
public class IcscfProperties {

    /** Address this I-CSCF advertises to peers (its externally reachable host/IP). */
    private String selfHost = "127.0.0.1";

    /** Port this I-CSCF advertises (normally equal to {@code ims.sip.port}). */
    private int selfPort = 5060;

    /** Home network domain / realm this I-CSCF serves. */
    private String homeDomain = "ims.snehal.com";

    /** Visited-network identifier sent to the HSS in the UAR. */
    private String visitedNetwork = "ims.snehal.com";

    /** Default S-CSCF used when the HSS returns capabilities rather than a server name. */
    private final Scscf scscf = new Scscf();

    /** Cx boundary tuning: bounds latency and thread occupancy on calls into the HSS. */
    private final Cx cx = new Cx();

    public static class Cx {
        /** Per-call gRPC deadline. */
        private Duration timeout = Duration.ofMillis(750);

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class Scscf {
        private String host = "s-cscf.ims-core.svc.cluster.local";
        private int port = 5060;
        private String transport = "udp";

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }
    }

    public String getSelfHost() { return selfHost; }
    public void setSelfHost(String selfHost) { this.selfHost = selfHost; }
    public int getSelfPort() { return selfPort; }
    public void setSelfPort(int selfPort) { this.selfPort = selfPort; }
    public String getHomeDomain() { return homeDomain; }
    public void setHomeDomain(String homeDomain) { this.homeDomain = homeDomain; }
    public String getVisitedNetwork() { return visitedNetwork; }
    public void setVisitedNetwork(String visitedNetwork) { this.visitedNetwork = visitedNetwork; }
    public Scscf getScscf() { return scscf; }
    public Cx getCx() { return cx; }
}
