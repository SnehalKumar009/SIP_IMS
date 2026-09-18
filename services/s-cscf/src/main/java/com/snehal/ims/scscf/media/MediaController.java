package com.snehal.ims.scscf.media;

/**
 * Seam for media-plane control.
 *
 * <p>The S-CSCF decides when a session needs media anchoring; how that is provisioned is
 * the media plane's problem. Phase 5 supplies an RTPEngine-backed implementation that
 * rewrites the SDP; until then {@link PassThroughMediaController} leaves the offer and
 * answer untouched, so the endpoints negotiate media directly.</p>
 */
public interface MediaController {

    /**
     * Called with the originating SDP offer.
     *
     * @return the SDP to forward onward — unchanged when no anchoring is applied
     */
    String offer(String callId, String fromTag, String sdp);

    /**
     * Called with the terminating SDP answer.
     *
     * @return the SDP to forward back — unchanged when no anchoring is applied
     */
    String answer(String callId, String fromTag, String toTag, String sdp);

    /** Releases any media resources held for the session. Must be safe to call twice. */
    void delete(String callId);
}
