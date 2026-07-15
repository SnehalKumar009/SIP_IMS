package com.snehal.ims.telecom.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reusable Micrometer instruments for SIP signaling, shared by every pod so dashboards
 * are uniform across the platform.
 *
 * <p>Method-tagged counters/timers are created lazily and cached to avoid meter churn.</p>
 */
public class SipMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> receivedByMethod = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> sentByStatus = new ConcurrentHashMap<>();
    private final Counter droppedCounter;
    private final Timer processingTimer;

    public SipMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.droppedCounter = Counter.builder("ims_sip_messages_dropped_total")
                .description("SIP messages dropped (rate limit / congestion / parse error)")
                .register(registry);
        this.processingTimer = Timer.builder("ims_sip_processing_seconds")
                .description("End-to-end handler processing time for a SIP message")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void recordReceived(String method) {
        receivedByMethod.computeIfAbsent(method, m ->
                Counter.builder("ims_sip_messages_received_total")
                        .tag("method", m)
                        .description("Inbound SIP requests/responses by method")
                        .register(registry)).increment();
    }

    public void recordSent(String statusOrMethod) {
        sentByStatus.computeIfAbsent(statusOrMethod, s ->
                Counter.builder("ims_sip_messages_sent_total")
                        .tag("kind", s)
                        .description("Outbound SIP messages by status code or method")
                        .register(registry)).increment();
    }

    public void recordDropped() {
        droppedCounter.increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopTimer(Timer.Sample sample) {
        sample.stop(processingTimer);
    }
}
