package com.snehal.ims.telecom.hep;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for HEPv3 capture shipping to the Homer / Heplify-Server stack.
 *
 * <p>Bound from {@code ims.hep.*}. Disabled cleanly when {@code enabled=false} so a
 * pod can run without an observability stack present.</p>
 */
@ConfigurationProperties(prefix = "ims.hep")
public class HepCaptureProperties {

    /** When false, capture calls become no-ops (no socket is opened). */
    private boolean enabled = true;

    /** Homer / Heplify-Server host. */
    private String host = "heplify-server.monitoring.svc.cluster.local";

    /** Homer / Heplify-Server HEP UDP port. */
    private int port = 9060;

    /** Numeric capture agent id identifying this pod in Homer. */
    private long captureAgentId = 2001;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public long getCaptureAgentId() { return captureAgentId; }
    public void setCaptureAgentId(long captureAgentId) { this.captureAgentId = captureAgentId; }
}
