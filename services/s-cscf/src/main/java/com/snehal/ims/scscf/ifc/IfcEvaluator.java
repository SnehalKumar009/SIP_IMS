package com.snehal.ims.scscf.ifc;

import com.snehal.ims.scscf.ifc.ServiceProfile.FilterCriterion;
import com.snehal.ims.scscf.ifc.ServiceProfile.SessionCase;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds each registered subscriber's service profile and decides which initial Filter
 * Criteria a request triggers.
 *
 * <p>There is no Application Server and no ISC interface yet, so a matched trigger is
 * logged and the request continues untouched (see docs/TECH_DEBT.md TD-011). Keeping the
 * evaluation real means the routing decision is already in the right place when an AS
 * arrives.</p>
 */
@Component
public class IfcEvaluator {

    private static final Logger log = LoggerFactory.getLogger(IfcEvaluator.class);

    private final Map<String, ServiceProfile> profilesByImpu = new ConcurrentHashMap<>();

    public void store(String impu, ServiceProfile profile) {
        if (impu != null && profile != null) {
            profilesByImpu.put(impu, profile);
        }
    }

    public void forget(String impu) {
        if (impu != null) {
            profilesByImpu.remove(impu);
        }
    }

    public ServiceProfile profileFor(String impu) {
        return profilesByImpu.getOrDefault(impu, ServiceProfile.EMPTY);
    }

    /** Filter criteria triggered by this request, in evaluation order. */
    public List<FilterCriterion> evaluate(String impu, String method, SessionCase sessionCase) {
        List<FilterCriterion> matched = profileFor(impu).filterCriteria().stream()
                .filter(criterion -> criterion.matches(method, sessionCase))
                .toList();
        if (!matched.isEmpty()) {
            log.debug("iFC matched {} criteria for impu={} method={} case={} (no AS configured, continuing)",
                    matched.size(), impu, method, sessionCase);
        }
        return matched;
    }
}
