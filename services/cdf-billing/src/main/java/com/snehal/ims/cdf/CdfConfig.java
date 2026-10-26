package com.snehal.ims.cdf;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class CdfConfig {

    private static final Logger log = LoggerFactory.getLogger(CdfConfig.class);

    /**
     * Retry policy for a batch that could not be stored. Spring Boot attaches this bean to
     * the listener container.
     *
     * <p>The backoff never runs out: while ClickHouse is unavailable the same batch is
     * retried (1 s, 2 s, 4 s … capped at 30 s) and its offsets stay uncommitted, so the
     * CDF pauses instead of dropping records. Lag on the {@code cdf-billing} consumer
     * group is the signal that this is happening.</p>
     */
    @Bean
    public CommonErrorHandler cdfErrorHandler(CdfProperties props) {
        ExponentialBackOff backOff = new ExponentialBackOff(
                props.getRetryInitialInterval().toMillis(), 2.0);
        backOff.setMaxInterval(props.getRetryMaxInterval().toMillis());
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
        handler.setRetryListeners(new RetryListener() {
            // A batch listener's failures arrive through the ConsumerRecords variant; its
            // default does nothing, which left every retry silent. Both variants log.
            @Override
            public void failedDelivery(ConsumerRecords<?, ?> records, Exception ex, int attempt) {
                log.warn("Storing charging batch of {} record(s) failed (attempt {}), will retry",
                        records.count(), attempt, ex);
            }

            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int attempt) {
                log.warn("Storing charging record {}-{}@{} failed (attempt {}), will retry",
                        record.topic(), record.partition(), record.offset(), attempt, ex);
            }
        });
        return handler;
    }
}
