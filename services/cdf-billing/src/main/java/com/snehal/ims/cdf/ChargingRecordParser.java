package com.snehal.ims.cdf;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a Kafka record value into a {@link ChargingRecord}, rejecting anything a CDR
 * cannot be built from. A rejected record goes to the dead-letter topic instead of
 * blocking its partition.
 */
@Component
public class ChargingRecordParser {

    private final ObjectMapper json;

    public ChargingRecordParser(ObjectMapper json) {
        // Unknown fields are tolerated so the S-CSCF can add fields before the CDF knows them.
        this.json = json.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** @throws InvalidChargingRecordException when the value is not a usable record */
    public ChargingRecord parse(String value) {
        ChargingRecord record;
        try {
            record = json.readValue(value, ChargingRecord.class);
        } catch (Exception e) {
            throw new InvalidChargingRecordException("not a charging record: " + e.getMessage(), e);
        }
        require(record.eventId() != null, "eventId missing");
        try {
            UUID.fromString(record.eventId());
        } catch (IllegalArgumentException e) {
            throw new InvalidChargingRecordException("eventId is not a UUID: " + record.eventId(), e);
        }
        require(ChargingRecord.SESSION_START.equals(record.type())
                || ChargingRecord.SESSION_END.equals(record.type()), "unknown type " + record.type());
        require(record.callId() != null && !record.callId().isBlank(), "callId missing");
        require(record.timestamp() != null, "timestamp missing");
        require(record.startedAt() != null, "startedAt missing");
        if (record.isEnd()) {
            require(!record.timestamp().isBefore(record.startedAt()), "END before its start");
        }
        return record;
    }

    private static void require(boolean condition, String problem) {
        if (!condition) {
            throw new InvalidChargingRecordException(problem, null);
        }
    }

    /** The record can never be stored; retrying will not help. */
    public static class InvalidChargingRecordException extends RuntimeException {
        public InvalidChargingRecordException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
