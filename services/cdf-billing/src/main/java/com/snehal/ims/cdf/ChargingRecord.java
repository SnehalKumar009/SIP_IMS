package com.snehal.ims.cdf;

import java.time.Instant;

/**
 * One record from the {@code ims-charging} topic.
 *
 * <p>Mirror of the S-CSCF's {@code ChargingEvent}: the JSON field names are the contract
 * between the two services and must stay identical (both test suites parse the same
 * sample record).</p>
 */
public record ChargingRecord(
        String eventId,
        String type,
        String callId,
        String from,
        String to,
        Instant timestamp,
        Instant startedAt,
        long durationMs,
        String endReason,
        String scscf) {

    public static final String SESSION_START = "IMS_SESSION_START";
    public static final String SESSION_END = "IMS_SESSION_END";

    public boolean isEnd() {
        return SESSION_END.equals(type);
    }
}
