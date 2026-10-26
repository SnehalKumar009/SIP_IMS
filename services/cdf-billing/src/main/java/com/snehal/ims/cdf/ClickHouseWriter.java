package com.snehal.ims.cdf;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Batch-inserts charging records and CDRs into ClickHouse ({@code ims_billing}).
 *
 * <p>One INSERT per table per Kafka batch: ClickHouse is built for few large inserts,
 * not many small ones. Timestamps are sent as UTC text with millisecond precision,
 * which ClickHouse parses straight into {@code DateTime64(3, 'UTC')} with no timezone
 * conversion in the JDBC driver.</p>
 *
 * <p>Any failure propagates to the Kafka listener, which then does not commit the batch
 * and retries it; duplicates from such a retry are collapsed by the tables'
 * ReplacingMergeTree keys.</p>
 */
@Component
public class ClickHouseWriter {

    private static final DateTimeFormatter UTC_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private static final String INSERT_EVENT = """
            INSERT INTO charging_events
              (event_id, event_type, call_id, from_uri, to_uri, event_time, started_at,
               duration_ms, end_reason, scscf, kafka_partition, kafka_offset)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String INSERT_CDR = """
            INSERT INTO cdr
              (call_id, from_uri, to_uri, started_at, ended_at, duration_ms, end_reason, scscf, end_event_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    /** A record together with where it was read from, kept for the audit table. */
    public record Received(ChargingRecord record, int partition, long offset) {
    }

    private final JdbcTemplate jdbc;

    public ClickHouseWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(List<Received> events, List<Cdr> cdrs) {
        if (!events.isEmpty()) {
            List<Object[]> rows = new ArrayList<>(events.size());
            for (Received r : events) {
                ChargingRecord c = r.record();
                rows.add(new Object[] {
                        c.eventId(), c.type(), c.callId(), text(c.from()), text(c.to()),
                        time(c.timestamp()), time(c.startedAt()), c.durationMs(),
                        text(c.endReason()), text(c.scscf()), r.partition(), r.offset()});
            }
            jdbc.batchUpdate(INSERT_EVENT, rows);
        }
        if (!cdrs.isEmpty()) {
            List<Object[]> rows = new ArrayList<>(cdrs.size());
            for (Cdr c : cdrs) {
                rows.add(new Object[] {
                        c.callId(), text(c.from()), text(c.to()), time(c.startedAt()), time(c.endedAt()),
                        c.durationMs(), text(c.endReason()), text(c.scscf()), c.endEventId()});
            }
            jdbc.batchUpdate(INSERT_CDR, rows);
        }
    }

    private static String time(Instant instant) {
        return UTC_MILLIS.format(instant);
    }

    /** ClickHouse String columns here are not Nullable; absent means empty. */
    private static String text(String value) {
        return value == null ? "" : value;
    }
}
