package com.snehal.ims.scscf.ifc;

import java.util.List;

/**
 * The subscriber's service profile as delivered in the Cx Server-Assignment answer:
 * the public identities it covers and the initial Filter Criteria that decide which
 * requests are handed to an Application Server.
 *
 * @param publicIdentities identities this profile applies to, used for P-Associated-URI
 * @param filterCriteria   iFC entries in evaluation order (lowest priority value first)
 */
public record ServiceProfile(List<String> publicIdentities, List<FilterCriterion> filterCriteria) {

    public static final ServiceProfile EMPTY = new ServiceProfile(List.of(), List.of());

    public ServiceProfile {
        publicIdentities = publicIdentities == null ? List.of() : List.copyOf(publicIdentities);
        filterCriteria = filterCriteria == null ? List.of() : List.copyOf(filterCriteria);
    }

    /**
     * One initial Filter Criterion.
     *
     * @param priority        evaluation order; lower runs first
     * @param methods         SIP methods that trigger it; empty matches any method
     * @param sessionCase     {@code ORIGINATING}, {@code TERMINATING} or null for both
     * @param applicationServer SIP URI of the AS to invoke
     * @param sessionContinued whether a failure to reach the AS lets the request continue
     *                         ({@code DefaultHandling = SESSION_CONTINUED})
     */
    public record FilterCriterion(
            int priority,
            List<String> methods,
            SessionCase sessionCase,
            String applicationServer,
            boolean sessionContinued) {

        public FilterCriterion {
            methods = methods == null ? List.of() : List.copyOf(methods);
        }

        public boolean matches(String method, SessionCase currentCase) {
            if (sessionCase != null && sessionCase != currentCase) {
                return false;
            }
            return methods.isEmpty() || methods.contains(method);
        }
    }

    public enum SessionCase {
        ORIGINATING,
        TERMINATING
    }
}
