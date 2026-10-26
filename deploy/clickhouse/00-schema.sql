-- ClickHouse schema for IMS charging. Mounted into /docker-entrypoint-initdb.d, which
-- the server image runs once, on first start with an empty data volume. To apply a
-- change to a running cluster, run the statements by hand (README.md).
--
-- Both tables are ReplacingMergeTree: Kafka delivers at least once, so the CDF may
-- insert the same record twice after a crash. Rows with the same ORDER BY key are
-- collapsed during background merges; query with FINAL to see de-duplicated data
-- immediately.

CREATE DATABASE IF NOT EXISTS ims_billing;

-- Every record from the ims-charging topic, as received (audit trail and replay check).
CREATE TABLE IF NOT EXISTS ims_billing.charging_events
(
    event_id        UUID,
    event_type      LowCardinality(String),      -- IMS_SESSION_START | IMS_SESSION_END
    call_id         String,
    from_uri        String,
    to_uri          String,
    event_time      DateTime64(3, 'UTC'),        -- answer time (START) or end time (END)
    started_at      DateTime64(3, 'UTC'),
    duration_ms     UInt64,                      -- as reported by the S-CSCF (END only)
    end_reason      LowCardinality(String),      -- BYE | TIMEOUT (END only)
    scscf           LowCardinality(String),
    kafka_partition UInt16,
    kafka_offset    UInt64,
    ingested_at     DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(event_time)
ORDER BY (call_id, event_id);

-- One row per completed call (built by the CDF from each END record).
CREATE TABLE IF NOT EXISTS ims_billing.cdr
(
    call_id         String,
    from_uri        String,
    to_uri          String,
    started_at      DateTime64(3, 'UTC'),
    ended_at        DateTime64(3, 'UTC'),
    duration_ms     UInt64,                      -- computed by the CDF: ended_at - started_at
    billed_seconds  UInt32 MATERIALIZED toUInt32(ceil(duration_ms / 1000)),  -- per-second, rounded up
    end_reason      LowCardinality(String),
    scscf           LowCardinality(String),
    end_event_id    UUID,
    ingested_at     DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(started_at)
ORDER BY call_id;
