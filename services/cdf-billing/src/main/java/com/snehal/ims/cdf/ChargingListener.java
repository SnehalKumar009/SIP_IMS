package com.snehal.ims.cdf;

import com.snehal.ims.cdf.ChargingRecordParser.InvalidChargingRecordException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumes the {@code ims-charging} topic in batches and stores each batch in ClickHouse.
 *
 * <p>Delivery guarantee: at least once. The container commits a batch's offsets only
 * after this method returns, i.e. after ClickHouse accepted both inserts. If ClickHouse
 * is down the method throws, nothing is committed, and the batch is retried (see
 * {@link CdfConfig}): a billing record is never skipped. A record that can never be
 * stored (unparseable) is sent to the dead-letter topic and the rest of the batch
 * carries on.</p>
 */
@Component
public class ChargingListener {

    private static final Logger log = LoggerFactory.getLogger(ChargingListener.class);

    private final ChargingRecordParser parser;
    private final ClickHouseWriter writer;
    private final KafkaTemplate<String, String> kafka;
    private final CdfProperties props;
    private final Counter stored;
    private final Counter invalid;
    private final Counter cdrs;
    private final Timer insertTimer;

    public ChargingListener(ChargingRecordParser parser, ClickHouseWriter writer,
                            KafkaTemplate<String, String> kafka, CdfProperties props,
                            MeterRegistry registry) {
        this.parser = parser;
        this.writer = writer;
        this.kafka = kafka;
        this.props = props;
        this.stored = Counter.builder("cdf_records_total").tag("result", "stored")
                .description("Charging records consumed, by outcome").register(registry);
        this.invalid = Counter.builder("cdf_records_total").tag("result", "invalid")
                .description("Charging records consumed, by outcome").register(registry);
        this.cdrs = Counter.builder("cdf_cdrs_total")
                .description("CDRs written to ClickHouse").register(registry);
        this.insertTimer = Timer.builder("cdf_clickhouse_insert_seconds")
                .description("Time to insert one Kafka batch into ClickHouse").register(registry);
    }

    @KafkaListener(topics = "${ims.cdf.topic}", batch = "true")
    public void onBatch(List<ConsumerRecord<String, String>> batch) throws Exception {
        List<ClickHouseWriter.Received> events = new ArrayList<>(batch.size());
        List<Cdr> newCdrs = new ArrayList<>();

        for (ConsumerRecord<String, String> rec : batch) {
            try {
                ChargingRecord record = parser.parse(rec.value());
                events.add(new ClickHouseWriter.Received(record, rec.partition(), rec.offset()));
                Cdr.from(record).ifPresent(newCdrs::add);
            } catch (InvalidChargingRecordException e) {
                deadLetter(rec, e);
            }
        }

        long start = System.nanoTime();
        writer.write(events, newCdrs);
        insertTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);

        stored.increment(events.size());
        cdrs.increment(newCdrs.size());
        for (ClickHouseWriter.Received r : events) {
            logStored(r.record());
        }
    }

    private void deadLetter(ConsumerRecord<String, String> rec, InvalidChargingRecordException e) throws Exception {
        invalid.increment();
        log.error("Invalid charging record at {}-{}@{} ({}), sending to {}: {}",
                rec.topic(), rec.partition(), rec.offset(), e.getMessage(), props.getDeadLetterTopic(), rec.value());
        // Synchronous: the batch's offsets must not be committed before the bad record is
        // safely parked, or it would be lost.
        kafka.send(props.getDeadLetterTopic(), rec.key(), rec.value()).get(10, TimeUnit.SECONDS);
    }

    private static void logStored(ChargingRecord r) {
        MDC.put("callId", r.callId());
        try {
            if (r.isEnd()) {
                log.info("Stored {} for callId={} from={} to={} durationMs={} reason={}",
                        r.type(), r.callId(), r.from(), r.to(), r.durationMs(), r.endReason());
            } else {
                log.info("Stored {} for callId={} from={} to={}", r.type(), r.callId(), r.from(), r.to());
            }
        } finally {
            MDC.remove("callId");
        }
    }
}
