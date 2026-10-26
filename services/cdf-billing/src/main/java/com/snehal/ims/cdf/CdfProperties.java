package com.snehal.ims.cdf;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** CDF configuration bound from {@code ims.cdf.*}. */
@ConfigurationProperties(prefix = "ims.cdf")
public class CdfProperties {

    /** Topic the S-CSCF publishes charging records to. */
    private String topic = "ims-charging";

    /** Where records that can never be stored are parked. */
    private String deadLetterTopic = "ims-charging-dlt";

    /** First wait before retrying a batch that failed to store (e.g. ClickHouse down). */
    private Duration retryInitialInterval = Duration.ofSeconds(1);

    /** Longest wait between retries; retries continue until the batch is stored. */
    private Duration retryMaxInterval = Duration.ofSeconds(30);

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getDeadLetterTopic() { return deadLetterTopic; }
    public void setDeadLetterTopic(String deadLetterTopic) { this.deadLetterTopic = deadLetterTopic; }
    public Duration getRetryInitialInterval() { return retryInitialInterval; }
    public void setRetryInitialInterval(Duration retryInitialInterval) { this.retryInitialInterval = retryInitialInterval; }
    public Duration getRetryMaxInterval() { return retryMaxInterval; }
    public void setRetryMaxInterval(Duration retryMaxInterval) { this.retryMaxInterval = retryMaxInterval; }
}
