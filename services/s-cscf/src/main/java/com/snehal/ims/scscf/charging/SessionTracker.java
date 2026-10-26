package com.snehal.ims.scscf.charging;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import com.snehal.ims.scscf.ScscfProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Tracks answered sessions so the end-of-call record can carry a duration.
 *
 * <p>Bounded and time-limited. A session whose BYE never arrives is evicted after
 * {@code ims.scscf.charging.max-session-duration}, and the expiry listener is told so
 * the call still gets an end record (reason TIMEOUT) instead of vanishing from billing.
 * A session ended by BYE is removed explicitly and does not trigger the listener. State
 * is per-pod (see docs/TECH_DEBT.md TD-028).</p>
 */
@Component
public class SessionTracker {

    private static final long MAX_SESSIONS = 100_000;

    public record Session(String callId, String from, String to, Instant startedAt) {
    }

    private final Cache<String, Session> sessions;

    /** Called for sessions removed by expiry or capacity, never for an explicit {@link #end}. */
    private volatile Consumer<Session> expiryListener = session -> { };

    @Autowired
    public SessionTracker(MeterRegistry registry, ScscfProperties props) {
        // The system scheduler makes expiry happen on time even when no call is active;
        // without it Caffeine only evicts lazily, on the next cache access.
        this(registry, props.getCharging().getMaxSessionDuration(), Ticker.systemTicker(),
                Scheduler.systemScheduler());
    }

    // Package-private so the expiry path can be driven by a fake clock in tests.
    SessionTracker(MeterRegistry registry, Duration maxSession, Ticker ticker, Scheduler scheduler) {
        this.sessions = Caffeine.newBuilder()
                .expireAfterWrite(maxSession)
                .maximumSize(MAX_SESSIONS)
                .ticker(ticker)
                .scheduler(scheduler)
                .removalListener((String callId, Session session, RemovalCause cause) -> {
                    if (session != null && cause.wasEvicted()) {
                        expiryListener.accept(session);
                    }
                })
                .build();
        Gauge.builder("ims_scscf_active_sessions", sessions, Cache::estimatedSize)
                .description("Sessions answered and not yet torn down")
                .register(registry);
    }

    /** Registers who is told when a session ends without a BYE. */
    public void onExpiry(Consumer<Session> listener) {
        this.expiryListener = listener;
    }

    public Session start(String callId, String from, String to, Instant at) {
        Session session = new Session(callId, from, to, at);
        sessions.put(callId, session);
        return session;
    }

    /** Removes and returns the session, or {@code null} when it was never tracked. */
    public Session end(String callId) {
        return sessions.asMap().remove(callId);
    }

    public boolean isTracked(String callId) {
        return sessions.getIfPresent(callId) != null;
    }

    /** Runs pending expiry work now (tests; production relies on the scheduler). */
    void cleanUp() {
        sessions.cleanUp();
    }
}
