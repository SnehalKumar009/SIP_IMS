package com.snehal.ims.telecom.sip;

import java.util.Properties;

/**
 * Builds the NIST-specific {@link Properties} map used to initialise the Jain SIP
 * {@code SipStack}, tuned for heavy enterprise signaling loads.
 *
 * <p>Kept as a pure, side-effect-free factory so it is trivially unit-testable and
 * reused identically by every pod.</p>
 */
public final class NistSipPropertiesFactory {

    private NistSipPropertiesFactory() {
    }

    public static Properties build(SipStackProperties props) {
        SipStackProperties.Nist nist = props.getNist();
        Properties p = new Properties();

        // Core stack identity + implementation package (NIST reference impl).
        p.setProperty("javax.sip.STACK_NAME", props.getStackName());

        // --- Performance / concurrency ---
        p.setProperty("gov.nist.javax.sip.THREAD_POOL_SIZE",
                Integer.toString(nist.getThreadPoolSize()));
        p.setProperty("gov.nist.javax.sip.REENTRANT_LISTENER",
                Boolean.toString(nist.isReentrantListener()));
        // Parse SIP messages off the IO thread so the socket loop never blocks.
        p.setProperty("gov.nist.javax.sip.MESSAGE_PROCESSOR_FACTORY",
                "gov.nist.javax.sip.stack.NioMessageProcessorFactory");
        p.setProperty("gov.nist.javax.sip.NIO_BLOCKING_MODE", "NONBLOCKING");

        // --- Buffers / limits ---
        p.setProperty("gov.nist.javax.sip.MAX_MESSAGE_SIZE",
                Integer.toString(nist.getMaxMessageSize()));
        p.setProperty("gov.nist.javax.sip.RECEIVE_UDP_BUFFER_SIZE",
                Integer.toString(nist.getReceiveUdpBufferSize()));
        p.setProperty("gov.nist.javax.sip.SEND_UDP_BUFFER_SIZE",
                Integer.toString(nist.getSendUdpBufferSize()));

        // --- Memory hygiene under load ---
        p.setProperty("gov.nist.javax.sip.AGGRESSIVE_CLEANUP",
                Boolean.toString(nist.isAggressiveCleanup()));
        p.setProperty("gov.nist.javax.sip.CONGESTION_CONTROL_ENABLED",
                Boolean.toString(nist.isCongestionControlEnabled()));
        p.setProperty("gov.nist.javax.sip.RELIABLE_CONNECTION_KEEP_ALIVE_TIMEOUT", "-1");

        // --- Automatic dialog support off: CSCFs are stateful proxies, not UAs ---
        p.setProperty("javax.sip.AUTOMATIC_DIALOG_SUPPORT", "off");

        // --- Diagnostics (0 == effectively off; raise only when debugging) ---
        p.setProperty("gov.nist.javax.sip.TRACE_LEVEL",
                Integer.toString(nist.getTraceLevel()));

        return p;
    }
}
