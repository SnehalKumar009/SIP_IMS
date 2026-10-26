package com.snehal.ims.cdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** CDR construction from charging records. */
class CdrTest {

    private static final Instant ANSWERED = Instant.parse("2026-10-26T12:50:08.111Z");
    private static final Instant ENDED = Instant.parse("2026-10-26T12:50:11.126Z");

    @Test
    void endRecordProducesACdrWhoseDurationComesFromItsOwnTimestamps() {
        // The S-CSCF's durationMs is deliberately wrong: the CDR must match the stored
        // start and end times, not trust the reported figure.
        ChargingRecord end = record(ChargingRecord.SESSION_END, ENDED, 999_999, "BYE");

        Cdr cdr = Cdr.from(end).orElseThrow();

        assertThat(cdr.durationMs()).isEqualTo(3015);
        assertThat(cdr.startedAt()).isEqualTo(ANSWERED);
        assertThat(cdr.endedAt()).isEqualTo(ENDED);
        assertThat(cdr.callId()).isEqualTo("call-1");
        assertThat(cdr.from()).isEqualTo("sip:alice@ims.snehal.com");
        assertThat(cdr.to()).isEqualTo("sip:bob@ims.snehal.com");
        assertThat(cdr.endEventId()).isEqualTo("5f0e2a43-7c1d-4a7e-9a55-1f6f2a3d9c10");
    }

    @Test
    void timeoutReasonIsCarriedIntoTheCdr() {
        Cdr cdr = Cdr.from(record(ChargingRecord.SESSION_END, ENDED, 3015, "TIMEOUT")).orElseThrow();

        assertThat(cdr.endReason()).isEqualTo("TIMEOUT");
    }

    @Test
    void startRecordProducesNoCdr() {
        Optional<Cdr> cdr = Cdr.from(record(ChargingRecord.SESSION_START, ANSWERED, 0, null));

        assertThat(cdr).isEmpty();
    }

    private static ChargingRecord record(String type, Instant timestamp, long durationMs, String reason) {
        return new ChargingRecord("5f0e2a43-7c1d-4a7e-9a55-1f6f2a3d9c10", type, "call-1",
                "sip:alice@ims.snehal.com", "sip:bob@ims.snehal.com", timestamp, ANSWERED,
                durationMs, reason, "sip:s-cscf-0.s-cscf.ims-core.svc.cluster.local:5060");
    }
}
