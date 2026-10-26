package com.snehal.ims.cdf;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Charging Data Record: one row per completed call.
 *
 * <p>The duration is computed here from the END record's own timestamps
 * ({@code timestamp - startedAt}) rather than copied from the S-CSCF's
 * {@code durationMs}, so the stored figure always matches the stored start and end
 * times to the millisecond.</p>
 */
public record Cdr(
        String callId,
        String from,
        String to,
        Instant startedAt,
        Instant endedAt,
        long durationMs,
        String endReason,
        String scscf,
        String endEventId) {

    /** A CDR for an END record; empty for a START, which only opens the call. */
    public static Optional<Cdr> from(ChargingRecord record) {
        if (!record.isEnd()) {
            return Optional.empty();
        }
        long durationMs = Math.max(0, Duration.between(record.startedAt(), record.timestamp()).toMillis());
        return Optional.of(new Cdr(record.callId(), record.from(), record.to(), record.startedAt(),
                record.timestamp(), durationMs, record.endReason(), record.scscf(), record.eventId()));
    }
}
