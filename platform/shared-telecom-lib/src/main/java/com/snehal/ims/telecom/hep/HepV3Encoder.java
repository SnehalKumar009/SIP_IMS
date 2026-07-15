package com.snehal.ims.telecom.hep;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Encodes a {@link HepMessage} into a HEPv3 wire frame.
 *
 * <p>Frame layout: a 4-byte ASCII id {@code "HEP3"}, a 2-byte total length, then a
 * sequence of generic chunks. Each chunk is {@code [vendor(2)][type(2)][length(2)][data]}
 * where {@code length} includes the 6-byte chunk header. All integers are big-endian.</p>
 *
 * <p>Stateless and therefore thread-safe.</p>
 */
public final class HepV3Encoder {

    private static final byte[] HEP3_ID = {'H', 'E', 'P', '3'};

    public byte[] encode(HepMessage m) {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream(256);

        writeByteChunk(chunks, HepChunkType.IP_PROTOCOL_FAMILY, (byte) m.ipFamily());
        writeByteChunk(chunks, HepChunkType.IP_PROTOCOL_ID, (byte) m.protocolId());

        if (m.ipFamily() == HepMessage.AF_INET6) {
            writeChunk(chunks, HepChunkType.IPV6_SRC_ADDR, m.srcAddr().getAddress());
            writeChunk(chunks, HepChunkType.IPV6_DST_ADDR, m.dstAddr().getAddress());
        } else {
            writeChunk(chunks, HepChunkType.IPV4_SRC_ADDR, m.srcAddr().getAddress());
            writeChunk(chunks, HepChunkType.IPV4_DST_ADDR, m.dstAddr().getAddress());
        }

        writeShortChunk(chunks, HepChunkType.SRC_PORT, (short) m.srcPort());
        writeShortChunk(chunks, HepChunkType.DST_PORT, (short) m.dstPort());

        long seconds = m.timestampMicros() / 1_000_000L;
        long micros = m.timestampMicros() % 1_000_000L;
        writeIntChunk(chunks, HepChunkType.TIMESTAMP_SECONDS, (int) seconds);
        writeIntChunk(chunks, HepChunkType.TIMESTAMP_MICROS, (int) micros);

        writeByteChunk(chunks, HepChunkType.PROTOCOL_TYPE, (byte) m.protocolType());
        writeIntChunk(chunks, HepChunkType.CAPTURE_AGENT_ID, (int) m.captureAgentId());

        if (m.correlationId() != null && !m.correlationId().isEmpty()) {
            writeChunk(chunks, HepChunkType.CORRELATION_ID,
                    m.correlationId().getBytes(StandardCharsets.UTF_8));
        }

        writeChunk(chunks, HepChunkType.PAYLOAD,
                m.payload().getBytes(StandardCharsets.UTF_8));

        int total = HEP3_ID.length + Short.BYTES + chunks.size();
        ByteBuffer buf = ByteBuffer.allocate(total); // big-endian by default
        buf.put(HEP3_ID);
        buf.putShort((short) total);
        buf.put(chunks.toByteArray());
        return buf.array();
    }

    // ---- chunk writers ----

    private void writeChunk(ByteArrayOutputStream out, short type, byte[] data) {
        int length = 6 + data.length; // vendor(2) + type(2) + length(2) + data
        ByteBuffer header = ByteBuffer.allocate(6);
        header.putShort(HepChunkType.VENDOR_GENERIC);
        header.putShort(type);
        header.putShort((short) length);
        out.writeBytes(header.array());
        out.writeBytes(data);
    }

    private void writeByteChunk(ByteArrayOutputStream out, short type, byte value) {
        writeChunk(out, type, new byte[]{value});
    }

    private void writeShortChunk(ByteArrayOutputStream out, short type, short value) {
        writeChunk(out, type, ByteBuffer.allocate(Short.BYTES).putShort(value).array());
    }

    private void writeIntChunk(ByteArrayOutputStream out, short type, int value) {
        writeChunk(out, type, ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }
}
