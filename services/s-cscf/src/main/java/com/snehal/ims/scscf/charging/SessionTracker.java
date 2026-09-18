package com.snehal.ims.scscf.charging;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Tracks answered sessions so the end-of-call record can carry a duration.
 *
 * <p>Bounded and time-limited: a dialog whose BYE never arrives is evicted rather than
 * leaked, at the cost of a missing end record. State is per-pod (see
 * docs/TECH_DEBT.md TD-028).</p>
 */
@Component
public class SessionTracker {

    /** Longest call this tracker will hold state for. */
    private static final Duration MAX_SESSION = Duration.ofHours(12);
    private static final long MAX_SESSIONS = 100_000;

    public record Session(String callId, String from, String to, Instant startedAt) {
    }

    private final Cache<String, Session> sessions = Caffeine.newBuilder()
            .expireAfterWrite(MAX_SESSION)
            .maximumSize(MAX_SESSIONS)
            .build();

    public SessionTracker(MeterRegistry registry) {
        Gauge.builder("ims_scscf_active_sessions", sessions, Cache::estimatedSize)
                .description("Sessions answered and not yet torn down")
                .register(registry);
    }

    public Session start(String callId, String from, String to, Instant at) {
        Session session = new Session(callId, from, to, at);
        sessions.put(callId, session);
        return session;
    }

    /** Removes and returns the session, or {@code null} when it was never tracked. */
    public Session end(String callId) {
        Session session = sessions.getIfPresent(callId);
        if (session != null) {
            sessions.invalidate(callId);
        }
        return session;
    }

    public boolean isTracked(String callId) {
        return sessions.getIfPresent(callId) != null;
    }
}
