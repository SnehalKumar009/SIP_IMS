package com.snehal.ims.scscf.charging;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A charging record emitted at a session boundary, consumed by the CDF ({@code cdf-billing}).
 *
 * <p>This record's JSON form is the contract on the {@code ims-charging} topic; the CDF
 * keeps a mirror of it. The END record is self-contained (it repeats {@code startedAt}),
 * so the CDF can build a CDR without remembering the START.</p>
 *
 * @param eventId    unique per record; lets the CDF drop a redelivered duplicate
 * @param type       {@code IMS_SESSION_START} or {@code IMS_SESSION_END}
 * @param callId     SIP Call-ID, the correlation key across both records (and the Kafka key)
 * @param from       calling party URI
 * @param to         called party URI
 * @param timestamp  when this boundary occurred (answer time for START, end time for END)
 * @param startedAt  when the call was answered
 * @param durationMs answered duration, END only
 * @param endReason  {@code BYE} or {@code TIMEOUT}, END only
 * @param scscf      server name of the S-CSCF that emitted the record
 */
public record ChargingEvent(
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

    /** The session ended with a BYE from either party. */
    public static final String END_BYE = "BYE";
    /** No BYE arrived before the session's maximum duration; the S-CSCF closed it. */
    public static final String END_TIMEOUT = "TIMEOUT";

    public static ChargingEvent start(String callId, String from, String to, Instant answeredAt,
                                      String scscf) {
        return new ChargingEvent(UUID.randomUUID().toString(), SESSION_START, callId, from, to,
                answeredAt, answeredAt, 0, null, scscf);
    }

    public static ChargingEvent end(String callId, String from, String to, Instant startedAt,
                                    Instant endedAt, String endReason, String scscf) {
        long durationMs = Math.max(0, Duration.between(startedAt, endedAt).toMillis());
        return new ChargingEvent(UUID.randomUUID().toString(), SESSION_END, callId, from, to,
                endedAt, startedAt, durationMs, endReason, scscf);
    }
}
