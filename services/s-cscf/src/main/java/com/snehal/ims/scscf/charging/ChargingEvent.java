package com.snehal.ims.scscf.charging;

import java.time.Instant;

/**
 * A charging record emitted at a session boundary, shaped for the CDF/billing consumer.
 *
 * @param type            {@code IMS_SESSION_START} or {@code IMS_SESSION_END}
 * @param callId          SIP Call-ID, the correlation key across both events
 * @param from            calling party URI
 * @param to              called party URI
 * @param timestamp       when the boundary occurred
 * @param durationSeconds session duration, populated only on the end event
 */
public record ChargingEvent(
        String type,
        String callId,
        String from,
        String to,
        Instant timestamp,
        long durationSeconds) {

    public static final String SESSION_START = "IMS_SESSION_START";
    public static final String SESSION_END = "IMS_SESSION_END";

    public static ChargingEvent start(String callId, String from, String to, Instant at) {
        return new ChargingEvent(SESSION_START, callId, from, to, at, 0);
    }

    public static ChargingEvent end(String callId, String from, String to, Instant at, long durationSeconds) {
        return new ChargingEvent(SESSION_END, callId, from, to, at, durationSeconds);
    }
}
