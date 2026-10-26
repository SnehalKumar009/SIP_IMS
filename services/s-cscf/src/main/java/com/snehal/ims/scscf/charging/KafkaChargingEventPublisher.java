package com.snehal.ims.scscf.charging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.snehal.ims.scscf.ScscfProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes charging records to Kafka for the CDF ({@code cdf-billing}).
 *
 * <p>The record is the JSON form of {@link ChargingEvent}, keyed by Call-ID so both
 * records of a call go to the same partition and are consumed in order. The producer is
 * configured with {@code acks=all} and idempotence (see application.yml): a charging
 * record, unlike a log line, must not be lost or duplicated by a retry.</p>
 *
 * <p>The send is asynchronous and bounded by {@code max.block.ms}, so a Kafka outage
 * costs at most that long on the calling thread and never fails the call. A record
 * that cannot be delivered is counted and logged at ERROR with its full content, which
 * keeps it recoverable from the log stream.</p>
 */
@Component
@ConditionalOnProperty(prefix = "ims.scscf.charging", name = "mode", havingValue = "kafka")
public class KafkaChargingEventPublisher implements ChargingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaChargingEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topic;
    private final Counter sent;
    private final Counter failed;

    public KafkaChargingEventPublisher(KafkaTemplate<String, String> kafka,
                                       ObjectMapper json,
                                       ScscfProperties props,
                                       MeterRegistry registry) {
        this.kafka = kafka;
        this.json = json;
        this.topic = props.getCharging().getTopic();
        this.sent = Counter.builder("ims_scscf_charging_events_total").tag("result", "sent")
                .description("Charging records published to Kafka, by outcome").register(registry);
        this.failed = Counter.builder("ims_scscf_charging_events_total").tag("result", "failed")
                .description("Charging records published to Kafka, by outcome").register(registry);
    }

    @Override
    public void publish(ChargingEvent event) {
        String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (Exception e) {
            failed.increment();
            log.error("Could not serialise charging record {} for callId={}", event.eventId(), event.callId(), e);
            return;
        }
        try {
            kafka.send(topic, event.callId(), payload).whenComplete((result, error) -> {
                if (error == null) {
                    sent.increment();
                    log.info("Charging record {} {} for callId={} -> {}-{}@{}", event.type(), event.eventId(),
                            event.callId(), topic, result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    failed.increment();
                    log.error("Charging record lost: {}", payload, error);
                }
            });
        } catch (Exception e) {
            // Thrown synchronously when the producer cannot even queue the record, e.g. no
            // broker metadata within max.block.ms.
            failed.increment();
            log.error("Charging record lost: {}", payload, e);
        }
    }
}
