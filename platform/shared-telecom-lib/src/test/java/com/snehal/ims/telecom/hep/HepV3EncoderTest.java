package com.snehal.ims.telecom.hep;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HepV3EncoderTest {

    private final HepV3Encoder encoder = new HepV3Encoder();

    private static final String SIP_PAYLOAD = """
            REGISTER sip:ims.example.com SIP/2.0\r
            Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK-abc\r
            From: <sip:alice@ims.example.com>;tag=1928\r
            To: <sip:alice@ims.example.com>\r
            Call-ID: test-call-id@10.0.0.1\r
            CSeq: 1 REGISTER\r
            Content-Length: 0\r
            \r
            """;

    @Test
    void encodesValidHep3HeaderAndTotalLength() throws Exception {
        byte[] frame = encoder.encode(sampleMessage());

        // "HEP3" magic
        assertThat(new String(frame, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("HEP3");

        // total length field (bytes 4-5) must equal the actual frame length
        int declaredTotal = Short.toUnsignedInt(ByteBuffer.wrap(frame, 4, 2).getShort());
        assertThat(declaredTotal).isEqualTo(frame.length);
    }

    @Test
    void embedsTheSipPayloadChunk() throws Exception {
        byte[] frame = encoder.encode(sampleMessage());
        String asText = new String(frame, StandardCharsets.UTF_8);
        assertThat(asText).contains("Call-ID: test-call-id@10.0.0.1");
        assertThat(asText).contains("REGISTER sip:ims.example.com");
    }

    @Test
    void containsExpectedChunkTypesInOrder() throws Exception {
        byte[] frame = encoder.encode(sampleMessage());
        ByteBuffer buf = ByteBuffer.wrap(frame);
        buf.position(6); // skip "HEP3" + total length

        boolean sawFamily = false, sawProtoId = false, sawSrcAddr = false,
                sawDstAddr = false, sawPayload = false;

        while (buf.remaining() >= 6) {
            buf.getShort();                 // vendor
            short type = buf.getShort();    // type
            int len = Short.toUnsignedInt(buf.getShort());
            int dataLen = len - 6;
            byte[] data = new byte[dataLen];
            buf.get(data);

            switch (type) {
                case HepChunkType.IP_PROTOCOL_FAMILY -> sawFamily = true;
                case HepChunkType.IP_PROTOCOL_ID -> sawProtoId = true;
                case HepChunkType.IPV4_SRC_ADDR -> sawSrcAddr = true;
                case HepChunkType.IPV4_DST_ADDR -> sawDstAddr = true;
                case HepChunkType.PAYLOAD -> sawPayload = true;
                default -> { /* other chunks ignored */ }
            }
        }

        assertThat(sawFamily).isTrue();
        assertThat(sawProtoId).isTrue();
        assertThat(sawSrcAddr).isTrue();
        assertThat(sawDstAddr).isTrue();
        assertThat(sawPayload).isTrue();
    }

    private HepMessage sampleMessage() throws Exception {
        return new HepMessage(
                HepMessage.AF_INET,
                HepMessage.PROTO_UDP,
                InetAddress.getByName("10.0.0.1"),
                InetAddress.getByName("10.0.0.2"),
                5060, 5060,
                1_700_000_000_000_000L,
                HepMessage.PAYLOAD_TYPE_SIP,
                2001L,
                "test-call-id@10.0.0.1",
                SIP_PAYLOAD);
    }
}
