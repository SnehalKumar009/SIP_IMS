package com.snehal.ims.scscf.sip;

import com.snehal.ims.scscf.ScscfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Coordinates shutdown so a rolling restart does not drop calls.
 *
 * <p>On stop the pod flips its readiness probe to refusing-traffic and starts answering
 * new out-of-dialog requests with 503 + Retry-After, while in-dialog traffic and
 * in-flight transactions continue to be served. The Jain SIP stack is torn down
 * afterwards, during bean destruction.</p>
 *
 * <p>Crucially it does <em>not</em> de-register subscribers towards the HSS: a restart is
 * not a de-registration, and clearing every assignment would black-hole terminating calls
 * for every user this pod serves until they each re-registered.</p>
 */
@Component
public class DrainCoordinator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DrainCoordinator.class);

    private final ApplicationEventPublisher events;
    private final ScscfProperties props;
    private volatile boolean running;

    public DrainCoordinator(ApplicationEventPublisher events, ScscfProperties props) {
        this.events = events;
        this.props = props;
    }

    public boolean isDraining() {
        return !running;
    }

    public int retryAfterSeconds() {
        return props.getRouting().getDrainingRetryAfterSeconds();
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        log.info("Draining: new out-of-dialog requests will be refused with 503");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stops before the SIP stack is destroyed, so the drain window actually exists. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }
}
