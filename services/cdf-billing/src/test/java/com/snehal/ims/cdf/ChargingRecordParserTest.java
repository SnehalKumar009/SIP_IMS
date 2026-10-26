package com.snehal.ims.cdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.snehal.ims.cdf.ChargingRecordParser.InvalidChargingRecordException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Parsing of the ims-charging contract. {@link #SAMPLE_END} is the same record the
 * S-CSCF's ChargingEventJsonTest produces, so the two services cannot drift apart.
 */
class ChargingRecordParserTest {

    static final String SAMPLE_END = """
            {"eventId":"5f0e2a43-7c1d-4a7e-9a55-1f6f2a3d9c10","type":"IMS_SESSION_END",
             "callId":"1-280962@10.255.0.10","from":"sip:alice@ims.snehal.com",
             "to":"sip:bob@ims.snehal.com","timestamp":"2026-10-26T12:50:11.126Z",
             "startedAt":"2026-10-26T12:50:08.111Z","durationMs":3015,"endReason":"BYE",
             "scscf":"sip:s-cscf-0.s-cscf.ims-core.svc.cluster.local:5060"}""";

    private final ChargingRecordParser parser = new ChargingRecordParser(JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build());

    @Test
    void parsesTheContractRecord() {
        ChargingRecord r = parser.parse(SAMPLE_END);

        assertThat(r.eventId()).isEqualTo("5f0e2a43-7c1d-4a7e-9a55-1f6f2a3d9c10");
        assertThat(r.isEnd()).isTrue();
        assertThat(r.callId()).isEqualTo("1-280962@10.255.0.10");
        assertThat(r.from()).isEqualTo("sip:alice@ims.snehal.com");
        assertThat(r.to()).isEqualTo("sip:bob@ims.snehal.com");
        assertThat(r.timestamp()).isEqualTo(Instant.parse("2026-10-26T12:50:11.126Z"));
        assertThat(r.startedAt()).isEqualTo(Instant.parse("2026-10-26T12:50:08.111Z"));
        assertThat(r.durationMs()).isEqualTo(3015);
        assertThat(r.endReason()).isEqualTo("BYE");
        assertThat(r.scscf()).isEqualTo("sip:s-cscf-0.s-cscf.ims-core.svc.cluster.local:5060");
    }

    @Test
    void fieldsTheCdfDoesNotKnowYetAreIgnored() {
        ChargingRecord r = parser.parse(SAMPLE_END.replace("\"scscf\"", "\"newField\":42,\"scscf\""));

        assertThat(r.callId()).isEqualTo("1-280962@10.255.0.10");
    }

    @Test
    void malformedJsonIsRejected() {
        assertThatThrownBy(() -> parser.parse("{not json"))
                .isInstanceOf(InvalidChargingRecordException.class);
    }

    @Test
    void recordWithoutCallIdIsRejected() {
        assertThatThrownBy(() -> parser.parse(SAMPLE_END.replace("\"callId\":\"1-280962@10.255.0.10\",", "")))
                .isInstanceOf(InvalidChargingRecordException.class)
                .hasMessageContaining("callId");
    }

    @Test
    void recordWithUnknownTypeIsRejected() {
        assertThatThrownBy(() -> parser.parse(SAMPLE_END.replace("IMS_SESSION_END", "IMS_SESSION_PAUSE")))
                .isInstanceOf(InvalidChargingRecordException.class)
                .hasMessageContaining("type");
    }

    @Test
    void endThatPrecedesItsStartIsRejected() {
        String backwards = SAMPLE_END.replace("12:50:11.126Z", "12:50:01.000Z");

        assertThatThrownBy(() -> parser.parse(backwards))
                .isInstanceOf(InvalidChargingRecordException.class)
                .hasMessageContaining("before its start");
    }
}
