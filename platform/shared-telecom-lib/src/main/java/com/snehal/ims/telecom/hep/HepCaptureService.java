package com.snehal.ims.telecom.hep;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.time.Instant;

/**
 * High-level entry point pods use to duplicate SIP messages to Homer.
 *
 * <p>Combines {@link HepV3Encoder} + {@link HepV3Sender} and records Micrometer metrics.
 * All work is guarded so a capture problem can never disturb signaling.</p>
 */
public class HepCaptureService {

    /** Direction of the captured message relative to this pod. */
    public enum Direction { INBOUND, OUTBOUND }

    private final HepV3Encoder encoder;
    private final HepV3Sender sender;
    private final HepCaptureProperties properties;
    private final Counter sentCounter;
    private final Counter failedCounter;

    public HepCaptureService(HepV3Encoder encoder,
                             HepV3Sender sender,
                             HepCaptureProperties properties,
                             MeterRegistry meterRegistry) {
        this.encoder = encoder;
        this.sender = sender;
        this.properties = properties;
        this.sentCounter = Counter.builder("ims_hep_capture_total")
                .description("HEPv3 frames successfully shipped to Homer")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("ims_hep_capture_failed_total")
                .description("HEPv3 frames that failed to ship to Homer")
                .register(meterRegistry);
    }

    /**
     * Capture a SIP message. Fire-and-forget; never throws.
     *
     * @param direction     inbound or outbound relative to this pod
     * @param transportUdp  {@code true} for UDP, {@code false} for TCP
     * @param src           source socket address of the original packet
     * @param srcPort       source port
     * @param dst           destination socket address of the original packet
     * @param dstPort       destination port
     * @param correlationId dialog/session correlation id (may be null)
     * @param sipPayload    raw SIP message text
     */
    public void capture(Direction direction,
                        boolean transportUdp,
                        InetAddress src, int srcPort,
                        InetAddress dst, int dstPort,
                        String correlationId,
                        String sipPayload) {
        if (!properties.isEnabled() || sipPayload == null) {
            return;
        }
        try {
            int ipFamily = src.getAddress().length == 16 ? HepMessage.AF_INET6 : HepMessage.AF_INET;
            HepMessage message = new HepMessage(
                    ipFamily,
                    transportUdp ? HepMessage.PROTO_UDP : HepMessage.PROTO_TCP,
                    src, dst, srcPort, dstPort,
                    micros(),
                    HepMessage.PAYLOAD_TYPE_SIP,
                    properties.getCaptureAgentId(),
                    correlationId,
                    sipPayload);

            boolean ok = sender.send(encoder.encode(message));
            (ok ? sentCounter : failedCounter).increment();
        } catch (Exception e) {
            failedCounter.increment();
        }
    }

    private static long micros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }
}
