package com.snehal.ims.scscf.registrar;

import java.time.Instant;
import java.util.List;

/**
 * One registered contact for an AoR: where the user can currently be reached, how to
 * get there, and when the binding lapses.
 *
 * @param aor         public identity the binding belongs to, e.g. {@code sip:alice@ims.snehal.com}
 * @param impi        private identity that authenticated the registration, needed for Cx SAR
 * @param contactUri  the UE's contact address, used as the retargeted Request-URI
 * @param callId      Call-ID of the REGISTER that created the binding (RFC 3261 §10.3)
 * @param cseq        CSeq of that REGISTER, used to reject replayed/reordered refreshes
 * @param path        Path headers from the REGISTER, topmost first: the return route to the UE
 * @param instanceId  {@code +sip.instance} of the UE, when it supports RFC 5626
 * @param regId       {@code reg-id} of this flow, when the UE supports RFC 5626
 * @param qValue      contact preference scaled by 1000 ({@code q=0.8} becomes 800)
 * @param expiresAt   absolute expiry
 */
public record Binding(
        String aor,
        String impi,
        String contactUri,
        String callId,
        long cseq,
        List<String> path,
        String instanceId,
        Integer regId,
        int qValue,
        Instant expiresAt) {

    public Binding {
        path = path == null ? List.of() : List.copyOf(path);
    }

    /**
     * Identity of the binding within its AoR. RFC 5626 keys a binding on the instance
     * and flow rather than the contact URI, so a UE that changes address keeps one
     * binding instead of accumulating stale ones.
     */
    public String key() {
        if (instanceId != null && !instanceId.isBlank()) {
            return instanceId + "|" + (regId == null ? 0 : regId);
        }
        return contactUri;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public long secondsRemaining(Instant now) {
        return Math.max(0, expiresAt.getEpochSecond() - now.getEpochSecond());
    }

    public Binding withExpiry(Instant newExpiry) {
        return new Binding(aor, impi, contactUri, callId, cseq, path, instanceId, regId, qValue, newExpiry);
    }
}
