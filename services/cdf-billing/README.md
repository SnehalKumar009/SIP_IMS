# cdf-billing — Charging Data Function

Turns the S-CSCF's charging records into stored billing data.

```
S-CSCF ─ IMS_SESSION_START (200 OK to INVITE) ─┐
       └ IMS_SESSION_END   (BYE, or TIMEOUT) ──┴► Kafka ims-charging ──► cdf-billing ──► ClickHouse ims_billing
                                                  key = Call-ID             group cdf-billing   charging_events, cdr
```

## The record (Kafka value, JSON)

| Field | Example | Notes |
|-------|---------|-------|
| `eventId` | `7710a7f5-…` | UUID per record; de-duplication key |
| `type` | `IMS_SESSION_START` / `IMS_SESSION_END` | |
| `callId` | `1-374191@10.255.0.10` | Also the Kafka key: both records of a call share a partition, in order |
| `from`, `to` | `sip:alice@ims.snehal.com` | |
| `timestamp` | `2026-10-26T13:19:35.314380717Z` | Answer time (START) or end time (END) |
| `startedAt` | `2026-10-26T13:19:31.799887959Z` | On both records, so an END is self-contained |
| `durationMs` | `3514` | END only, as measured by the S-CSCF |
| `endReason` | `BYE` / `TIMEOUT` | END only. TIMEOUT: no BYE within `ims.scscf.charging.max-session-duration` (12 h) |
| `scscf` | `sip:s-cscf-0.s-cscf…:5060` | Emitting S-CSCF |

The S-CSCF's `ChargingEvent` and this service's `ChargingRecord` mirror each other; both test
suites parse the same sample record, so the contract cannot drift silently.

## What the CDF does with a batch

1. Parses each record; one that can never be stored (bad JSON, missing `callId`, END before its
   start…) goes to `ims-charging-dlt` and the rest carry on.
2. Inserts every record into `charging_events` (with its Kafka partition/offset) and, for each
   END, a row into `cdr` whose `duration_ms` is computed from the END's own `startedAt` and
   `timestamp`. `billed_seconds` is per-second, rounded up.
3. Only then does the container commit the batch's offsets.

**Delivery:** at least once. If ClickHouse is down, the batch is retried with backoff (1 s → 30 s)
without end and nothing is committed: consumer lag grows, no record is skipped. A record
retried after a crash may be inserted twice; both tables are `ReplacingMergeTree` keyed on the
call (and event), so duplicates collapse — query with `FINAL` to see that immediately.

## Configuration (`ims.cdf.*`, `spring.kafka.*`, `spring.datasource.*`)

| Key | Default |
|-----|---------|
| `ims.cdf.topic` / `ims.cdf.dead-letter-topic` | `ims-charging` / `ims-charging-dlt` |
| `ims.cdf.retry-initial-interval` / `retry-max-interval` | `1s` / `30s` |
| `spring.kafka.consumer.group-id` | `cdf-billing` |
| `spring.datasource.url` | `jdbc:clickhouse://clickhouse.ims-core.svc.cluster.local:8123/ims_billing` |

Credentials come from the `clickhouse-credentials` Secret (`CDF_CLICKHOUSE_USER/PASSWORD`).

**Dependency note:** `clickhouse-jdbc` 0.10.0 needs HttpClient 5.4 / HttpCore 5.3, newer than
Spring Boot 3.3 pins; `pom.xml` overrides both for this module only. With the older HttpCore,
every pool connection fails with a `NoSuchMethodError` and the consumer stalls silently.

## Metrics

`cdf_records_total{result="stored|invalid"}`, `cdf_cdrs_total`, `cdf_clickhouse_insert_seconds`,
plus the Kafka consumer metrics (`kafka_consumer_*`). On the S-CSCF:
`ims_scscf_charging_events_total{result="sent|failed"}`.

## Build and deploy

```bash
mvn -pl services/cdf-billing -am package
docker build -t localhost:32000/ims/cdf-billing:latest services/cdf-billing
docker push localhost:32000/ims/cdf-billing:latest
microk8s kubectl apply -k deploy/clickhouse                 # once
microk8s kubectl apply -f deploy/manifests/cdf-billing/cdf-billing.yaml
```

## Verify a call was billed

```bash
CH="microk8s kubectl -n ims-core exec clickhouse-0 -- clickhouse-client --user cdf --password cdf-change-me"
$CH -q "SELECT call_id, from_uri, to_uri, started_at, duration_ms, billed_seconds, end_reason
        FROM ims_billing.cdr FINAL ORDER BY started_at DESC LIMIT 10 FORMAT PrettyCompact"

# Is the CDF keeping up? LAG should be 0.
microk8s kubectl -n kafka exec ims-kafka-dual-role-0 -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server ims-kafka-kafka-bootstrap.kafka.svc.cluster.local:9092 --describe --group cdf-billing
```
