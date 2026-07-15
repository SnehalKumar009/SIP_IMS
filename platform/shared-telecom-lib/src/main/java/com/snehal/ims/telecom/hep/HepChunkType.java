package com.snehal.ims.telecom.hep;

/**
 * HEPv3 (Homer Encapsulation Protocol, version 3) generic-chunk type identifiers
 * under the default vendor id {@code 0x0000}.
 *
 * <p>Reference: <a href="https://github.com/sipcapture/HEP">sipcapture/HEP</a>.</p>
 */
public final class HepChunkType {

    private HepChunkType() {
    }

    /** Default (generic) vendor id. */
    public static final short VENDOR_GENERIC = 0x0000;

    public static final short IP_PROTOCOL_FAMILY = 0x0001; // 1 byte: 2=AF_INET, 10=AF_INET6
    public static final short IP_PROTOCOL_ID     = 0x0002; // 1 byte: 6=TCP, 17=UDP
    public static final short IPV4_SRC_ADDR      = 0x0003; // 4 bytes
    public static final short IPV4_DST_ADDR      = 0x0004; // 4 bytes
    public static final short IPV6_SRC_ADDR      = 0x0005; // 16 bytes
    public static final short IPV6_DST_ADDR      = 0x0006; // 16 bytes
    public static final short SRC_PORT           = 0x0007; // 2 bytes
    public static final short DST_PORT           = 0x0008; // 2 bytes
    public static final short TIMESTAMP_SECONDS  = 0x0009; // 4 bytes
    public static final short TIMESTAMP_MICROS   = 0x000a; // 4 bytes
    public static final short PROTOCOL_TYPE      = 0x000b; // 1 byte: 1=SIP
    public static final short CAPTURE_AGENT_ID   = 0x000c; // 4 bytes
    public static final short KEEP_ALIVE_TIMER   = 0x000d; // 2 bytes
    public static final short AUTHENTICATE_KEY   = 0x000e; // string
    public static final short PAYLOAD            = 0x000f; // string (captured packet)
    public static final short CORRELATION_ID     = 0x0011; // string
}
