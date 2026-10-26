package com.snehal.ims.scscf.charging;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Scheduler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * A call whose BYE never arrives must still reach billing: when its session expires the
 * tracker hands it to the expiry listener, which SessionHandler turns into an END record
 * with reason TIMEOUT. A call ended normally must not trigger it.
 */
class SessionTrackerTest {

    private static final Duration MAX_SESSION = Duration.ofHours(12);

    private final AtomicLong nanos = new AtomicLong();
    private final List<SessionTracker.Session> expired = new ArrayList<>();
    private final SessionTracker tracker = new SessionTracker(
            new SimpleMeterRegistry(), MAX_SESSION, nanos::get, Scheduler.disabledScheduler());

    {
        tracker.onExpiry(expired::add);
    }

    @Test
    void sessionWithoutByeIsHandedToTheExpiryListener() {
        tracker.start("call-1", "sip:alice@ims.snehal.com", "sip:bob@ims.snehal.com", Instant.now());

        advance(MAX_SESSION.plusSeconds(1));
        tracker.cleanUp();

        assertThat(expired).extracting(SessionTracker.Session::callId).containsExactly("call-1");
        assertThat(tracker.isTracked("call-1")).isFalse();
    }

    @Test
    void sessionStillInsideItsMaximumDurationIsKept() {
        tracker.start("call-1", "a", "b", Instant.now());

        advance(MAX_SESSION.minusSeconds(1));
        tracker.cleanUp();

        assertThat(expired).isEmpty();
        assertThat(tracker.isTracked("call-1")).isTrue();
    }

    @Test
    void sessionEndedByByeDoesNotTriggerTheExpiryListener() {
        tracker.start("call-1", "a", "b", Instant.now());

        SessionTracker.Session ended = tracker.end("call-1");
        advance(MAX_SESSION.plusSeconds(1));
        tracker.cleanUp();

        assertThat(ended).isNotNull();
        assertThat(expired).isEmpty();
    }

    @Test
    void endingAnUnknownCallReturnsNothing() {
        assertThat(tracker.end("never-started")).isNull();
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }
}
