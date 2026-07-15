package com.snehal.ims.telecom.sip;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the Jain SIP stack shared by every signaling pod.
 *
 * <p>Bound from the {@code ims.sip.*} namespace (ConfigMap / application.yml). Each pod
 * overrides {@link #host}, {@link #port} and {@link #stackName} for its own role.</p>
 */
@ConfigurationProperties(prefix = "ims.sip")
public class SipStackProperties {

    /** Master switch: when false the SIP stack is not instantiated (useful for non-signaling pods). */
    private boolean enabled = true;

    /** Logical Jain SIP stack name (surfaces in NIST logs). */
    private String stackName = "ims-sip-stack";

    /** Bind address. {@code 0.0.0.0} listens on all interfaces (default inside a pod). */
    private String host = "0.0.0.0";

    /** SIP listening port. */
    private int port = 5060;

    /** Primary transport for the listening point: {@code udp} or {@code tcp}. */
    private String transport = "udp";

    /** NIST tuning knobs (heavy enterprise defaults). */
    private final Nist nist = new Nist();

    public static class Nist {
        /** gov.nist.javax.sip.THREAD_POOL_SIZE */
        private int threadPoolSize = 64;
        /** gov.nist.javax.sip.REENTRANT_LISTENER — allows the stack to re-enter the listener. */
        private boolean reentrantListener = true;
        /** gov.nist.javax.sip.MAX_MESSAGE_SIZE (bytes). */
        private int maxMessageSize = 65_536;
        /** gov.nist.javax.sip.RECEIVE_UDP_BUFFER_SIZE (bytes). */
        private int receiveUdpBufferSize = 8 * 1024 * 1024;
        /** gov.nist.javax.sip.SEND_UDP_BUFFER_SIZE (bytes). */
        private int sendUdpBufferSize = 8 * 1024 * 1024;
        /** gov.nist.javax.sip.AGGRESSIVE_CLEANUP — release transaction memory eagerly. */
        private boolean aggressiveCleanup = true;
        /** Enable congestion control by dropping requests when the queue is saturated. */
        private boolean congestionControlEnabled = true;
        /** gov.nist.javax.sip.NIST server/transaction log level (32=OFF-ish, use TRACE only when debugging). */
        private int traceLevel = 0;

        public int getThreadPoolSize() { return threadPoolSize; }
        public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }
        public boolean isReentrantListener() { return reentrantListener; }
        public void setReentrantListener(boolean reentrantListener) { this.reentrantListener = reentrantListener; }
        public int getMaxMessageSize() { return maxMessageSize; }
        public void setMaxMessageSize(int maxMessageSize) { this.maxMessageSize = maxMessageSize; }
        public int getReceiveUdpBufferSize() { return receiveUdpBufferSize; }
        public void setReceiveUdpBufferSize(int receiveUdpBufferSize) { this.receiveUdpBufferSize = receiveUdpBufferSize; }
        public int getSendUdpBufferSize() { return sendUdpBufferSize; }
        public void setSendUdpBufferSize(int sendUdpBufferSize) { this.sendUdpBufferSize = sendUdpBufferSize; }
        public boolean isAggressiveCleanup() { return aggressiveCleanup; }
        public void setAggressiveCleanup(boolean aggressiveCleanup) { this.aggressiveCleanup = aggressiveCleanup; }
        public boolean isCongestionControlEnabled() { return congestionControlEnabled; }
        public void setCongestionControlEnabled(boolean congestionControlEnabled) { this.congestionControlEnabled = congestionControlEnabled; }
        public int getTraceLevel() { return traceLevel; }
        public void setTraceLevel(int traceLevel) { this.traceLevel = traceLevel; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStackName() { return stackName; }
    public void setStackName(String stackName) { this.stackName = stackName; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }
    public Nist getNist() { return nist; }
}
