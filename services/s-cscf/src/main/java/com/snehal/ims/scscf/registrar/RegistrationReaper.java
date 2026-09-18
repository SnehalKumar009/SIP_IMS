package com.snehal.ims.scscf.registrar;

import com.snehal.ims.hss.grpc.ServerAssignmentType;
import com.snehal.ims.scscf.ScscfMetrics;
import com.snehal.ims.scscf.cx.CxClient;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps lapsed bindings and tells the HSS when a subscriber has gone silent.
 *
 * <p>Without this the HSS would keep pointing terminating traffic at this S-CSCF for a
 * user whose registration has already expired, and every INVITE for them would be
 * answered with 404 instead of being routed to voicemail or an unregistered-user
 * service.</p>
 */
@Component
public class RegistrationReaper {

    private static final Logger log = LoggerFactory.getLogger(RegistrationReaper.class);

    private final RegistrationStore store;
    private final CxClient cx;
    private final ScscfMetrics metrics;

    public RegistrationReaper(RegistrationStore store, CxClient cx, ScscfMetrics metrics) {
        this.store = store;
        this.cx = cx;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${ims.scscf.registration.reaper-interval:30s}")
    public void sweep() {
        List<Binding> expired = store.removeExpired(Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        metrics.recordBindingsExpired(expired.size());

        // Only tell the HSS about AoRs that have no surviving binding: a UE losing one
        // of several flows is not a de-registration.
        Set<String> seen = new HashSet<>();
        for (Binding binding : expired) {
            if (!seen.add(binding.aor()) || !store.find(binding.aor()).isEmpty()) {
                continue;
            }
            cx.serverAssignment(binding.aor(), binding.impi(),
                    ServerAssignmentType.SERVER_ASSIGNMENT_TIMEOUT_DEREGISTRATION);
            log.info("Registration expired, de-registered aor={} towards HSS", binding.aor());
        }
    }
}
