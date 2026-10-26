package com.snehal.ims.scscf.charging;

import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default charging publisher: writes the record to the structured log. Active when
 * {@code ims.scscf.charging.mode=log}; {@link KafkaChargingEventPublisher} is used for {@code kafka}.
 */
@Component
@ConditionalOnProperty(prefix = "ims.scscf.charging", name = "mode", havingValue = "log", matchIfMissing = true)
public class LoggingChargingEventPublisher implements ChargingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingChargingEventPublisher.class);

    @Override
    public void publish(ChargingEvent event) {
        try {
            log.info("charging event",
                    StructuredArguments.kv("eventId", event.eventId()),
                    StructuredArguments.kv("eventType", event.type()),
                    StructuredArguments.kv("callId", event.callId()),
                    StructuredArguments.kv("from", event.from()),
                    StructuredArguments.kv("to", event.to()),
                    StructuredArguments.kv("startedAt", String.valueOf(event.startedAt())),
                    StructuredArguments.kv("durationMs", event.durationMs()),
                    StructuredArguments.kv("endReason", event.endReason()));
        } catch (Exception e) {
            log.warn("Failed to emit charging event for callId={}", event.callId(), e);
        }
    }
}
