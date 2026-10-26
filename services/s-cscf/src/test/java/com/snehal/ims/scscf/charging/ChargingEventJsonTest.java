package com.snehal.ims.scscf.charging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The JSON form of {@link ChargingEvent} is the contract on the ims-charging topic.
 * cdf-billing's ChargingRecordParserTest parses the same sample END record, so a field
 * renamed on either side fails one of the two suites.
 */
class ChargingEventJsonTest {

    // Configured like Spring Boot's auto-configured ObjectMapper, which the publisher uses.
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static final String SCSCF = "sip:s-cscf-0.s-cscf.ims-core.svc.cluster.local:5060";
    private static final Instant ANSWERED = Instant.parse("2026-10-26T12:50:08.111Z");
    private static final Instant ENDED = Instant.parse("2026-10-26T12:50:11.126Z");

    /** Shared with cdf-billing's ChargingRecordParserTest (eventId aside). */
    static final String SAMPLE_END = """
            {"eventId":"5f0e2a43-7c1d-4a7e-9a55-1f6f2a3d9c10","type":"IMS_SESSION_END",
             "callId":"1-280962@10.255.0.10","from":"sip:alice@ims.snehal.com",
             "to":"sip:bob@ims.snehal.com","timestamp":"2026-10-26T12:50:11.126Z",
             "startedAt":"2026-10-26T12:50:08.111Z","durationMs":3015,"endReason":"BYE",
             "scscf":"sip:s-cscf-0.s-cscf.ims-core.svc.cluster.local:5060"}""";

    @Test
    void endRecordSerialisesToTheContract() throws Exception {
        ChargingEvent end = ChargingEvent.end("1-280962@10.255.0.10", "sip:alice@ims.snehal.com",
                "sip:bob@ims.snehal.com", ANSWERED, ENDED, ChargingEvent.END_BYE, SCSCF);

        JsonNode actual = JSON.readTree(JSON.writeValueAsString(end));
        JsonNode expected = JSON.readTree(SAMPLE_END);

        assertThat(fieldNames(actual)).containsExactlyElementsOf(fieldNames(expected));
        // eventId is random per record; everything else must match exactly.
        ((com.fasterxml.jackson.databind.node.ObjectNode) actual).remove("eventId");
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected).remove("eventId");
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void durationIsComputedFromAnswerToEndInMilliseconds() {
        ChargingEvent end = ChargingEvent.end("c", "a", "b", ANSWERED, ENDED, ChargingEvent.END_BYE, SCSCF);

        assertThat(end.durationMs()).isEqualTo(3015);
    }

    @Test
    void startRecordCarriesTheAnswerTimeAndNoEndFields() throws Exception {
        ChargingEvent start = ChargingEvent.start("c", "a", "b", ANSWERED, SCSCF);
        JsonNode json = JSON.readTree(JSON.writeValueAsString(start));

        assertThat(json.get("type").asText()).isEqualTo("IMS_SESSION_START");
        assertThat(json.get("timestamp").asText()).isEqualTo("2026-10-26T12:50:08.111Z");
        assertThat(json.get("startedAt").asText()).isEqualTo("2026-10-26T12:50:08.111Z");
        assertThat(json.get("durationMs").asLong()).isZero();
        assertThat(json.get("endReason").isNull()).isTrue();
    }

    @Test
    void everyRecordGetsItsOwnEventId() {
        ChargingEvent a = ChargingEvent.start("c", "a", "b", ANSWERED, SCSCF);
        ChargingEvent b = ChargingEvent.start("c", "a", "b", ANSWERED, SCSCF);

        assertThat(a.eventId()).isNotEqualTo(b.eventId());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
