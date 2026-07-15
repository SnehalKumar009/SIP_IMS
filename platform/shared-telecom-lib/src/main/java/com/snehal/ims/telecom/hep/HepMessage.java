package com.snehal.ims.telecom.hep;

import java.net.InetAddress;

/**
 * Immutable description of a single SIP message to be encapsulated as a HEPv3 frame
 * and shipped to Homer.
 *
 * @param ipFamily        address family: {@code 2} (AF_INET) or {@code 10} (AF_INET6)
 * @param protocolId      L4 protocol: {@code 17} (UDP) or {@code 6} (TCP)
 * @param srcAddr         source IP of the original SIP packet
 * @param dstAddr         destination IP of the original SIP packet
 * @param srcPort         source port
 * @param dstPort         destination port
 * @param timestampMicros capture time in microseconds since the epoch
 * @param protocolType    HEP payload protocol type ({@code 1} = SIP)
 * @param captureAgentId  numeric id identifying the capturing pod
 * @param correlationId   optional dialog/session correlation id (nullable)
 * @param payload         the raw SIP message text
 */
public record HepMessage(
        int ipFamily,
        int protocolId,
        InetAddress srcAddr,
        InetAddress dstAddr,
        int srcPort,
        int dstPort,
        long timestampMicros,
        int protocolType,
        long captureAgentId,
        String correlationId,
        String payload) {

    public static final int AF_INET = 2;
    public static final int AF_INET6 = 10;
    public static final int PROTO_TCP = 6;
    public static final int PROTO_UDP = 17;
    public static final int PAYLOAD_TYPE_SIP = 1;

    public HepMessage {
        if (srcAddr == null || dstAddr == null) {
            throw new IllegalArgumentException("srcAddr and dstAddr must not be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
    }
}
