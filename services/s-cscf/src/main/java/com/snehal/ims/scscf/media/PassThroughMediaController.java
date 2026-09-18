package com.snehal.ims.scscf.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default media controller: no anchoring, SDP forwarded verbatim, media flows directly
 * between endpoints. Correct whenever both endpoints are mutually routable; replaced by
 * the RTPEngine controller in Phase 5 (see docs/TECH_DEBT.md TD-012).
 */
@Component
@ConditionalOnProperty(prefix = "ims.scscf.media", name = "mode", havingValue = "passthrough", matchIfMissing = true)
public class PassThroughMediaController implements MediaController {

    @Override
    public String offer(String callId, String fromTag, String sdp) {
        return sdp;
    }

    @Override
    public String answer(String callId, String fromTag, String toTag, String sdp) {
        return sdp;
    }

    @Override
    public void delete(String callId) {
        // nothing anchored, nothing to release
    }
}
