package com.snehal.ims.scscf.sip;

import com.snehal.ims.scscf.charging.ChargingEvent;
import com.snehal.ims.scscf.charging.ChargingEventPublisher;
import com.snehal.ims.scscf.charging.SessionTracker;
import com.snehal.ims.scscf.ifc.IfcEvaluator;
import com.snehal.ims.scscf.ifc.ServiceProfile.SessionCase;
import com.snehal.ims.scscf.media.MediaController;
import com.snehal.ims.scscf.registrar.Binding;
import com.snehal.ims.scscf.registrar.RegistrationStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.sip.ClientTransaction;
import javax.sip.ServerTransaction;
import javax.sip.SipProvider;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.ToHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Session control for everything that is not a REGISTER.
 *
 * <p>Initial requests are classified as originating or terminating, evaluated against the
 * subscriber's iFC, and either retargeted onto a registered contact (terminating) or
 * forwarded onward with an asserted identity (originating). In-dialog requests are simply
 * routed by their existing Route set — retargeting or Record-Routing them again would
 * corrupt the dialog.</p>
 */
@Component
public class SessionHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionHandler.class);
    private static final String SDP_SUBTYPE = "sdp";

    private final ProxyForwarder proxy;
    private final RegistrationStore store;
    private final SessionCaseResolver sessionCases;
    private final IfcEvaluator ifc;
    private final MediaController media;
    private final SessionTracker sessions;
    private final ChargingEventPublisher charging;

    public SessionHandler(ProxyForwarder proxy,
                          RegistrationStore store,
                          SessionCaseResolver sessionCases,
                          IfcEvaluator ifc,
                          MediaController media,
                          SessionTracker sessions,
                          ChargingEventPublisher charging) {
        this.proxy = proxy;
        this.store = store;
        this.sessionCases = sessionCases;
        this.ifc = ifc;
        this.media = media;
        this.sessions = sessions;
        this.charging = charging;
    }

    public ClientTransaction handle(SipProvider provider, Request request, ServerTransaction serverTx,
                                    String method, String callId) throws Exception {
        Request out = (Request) request.clone();
        proxy.popOwnRoute(out);

        if (!proxy.decrementMaxForwards(out)) {
            proxy.reject(serverTx, request, Response.TOO_MANY_HOPS);
            return null;
        }

        if (isInDialog(request)) {
            return forwardInDialog(provider, out, serverTx, method, callId);
        }
        return forwardInitial(provider, out, serverTx, method, callId);
    }

    // --------------------------------------------------------------- initial requests

    private ClientTransaction forwardInitial(SipProvider provider, Request out, ServerTransaction serverTx,
                                             String method, String callId) throws Exception {
        SessionCase sessionCase = sessionCases.resolve(out);
        String servedUser = sessionCase == SessionCase.TERMINATING
                ? SipMessages.requestUriAor(out)
                : SipMessages.fromAor(out);
        ifc.evaluate(servedUser, method, sessionCase);

        if (sessionCase == SessionCase.TERMINATING && !retarget(out, serverTx, servedUser)) {
            return null;
        }
        if (sessionCase == SessionCase.ORIGINATING) {
            assertIdentity(out, servedUser);
        }

        if (isDialogForming(method)) {
            proxy.addRecordRoute(out);
        }
        applyMediaOffer(out, callId);

        proxy.addOwnVia(out);
        return proxy.sendStateful(provider, out, serverTx, method, callId);
    }

    /**
     * Rewrites the Request-URI onto a registered contact and replays the Path the UE
     * registered through, so the request retraces the proxies that can reach it.
     *
     * @return {@code false} when the transaction has already been rejected
     */
    private boolean retarget(Request out, ServerTransaction serverTx, String aor) throws Exception {
        List<Binding> bindings = store.find(aor);
        if (bindings.isEmpty()) {
            proxy.reject(serverTx, out, Response.TEMPORARILY_UNAVAILABLE);
            return false;
        }
        // Highest-priority binding only; parallel forking is not implemented (TD-016).
        Binding binding = bindings.get(0);
        out.setRequestURI(proxy.stack().getAddressFactory().createURI(binding.contactUri()));
        proxy.applyPathAsRoute(out, binding.path());
        log.debug("Retargeted {} onto {}", aor, binding.contactUri());
        return true;
    }

    /**
     * Replaces any caller-supplied identity headers with one this S-CSCF vouches for.
     * A UE-asserted P-Asserted-Identity must never be trusted: it is the whole basis of
     * downstream billing and caller display.
     */
    private void assertIdentity(Request out, String servedUser) throws Exception {
        out.removeHeader(SipMessages.P_ASSERTED_IDENTITY);
        out.removeHeader("P-Preferred-Identity");
        if (servedUser == null || store.find(servedUser).isEmpty()) {
            return;
        }
        out.addHeader(proxy.stack().getHeaderFactory()
                .createHeader(SipMessages.P_ASSERTED_IDENTITY, "<" + servedUser + ">"));
    }

    // -------------------------------------------------------------- in-dialog requests

    private ClientTransaction forwardInDialog(SipProvider provider, Request out, ServerTransaction serverTx,
                                              String method, String callId) throws Exception {
        if (Request.BYE.equals(method)) {
            endSession(callId);
        }
        applyMediaOffer(out, callId);
        proxy.addOwnVia(out);
        return proxy.sendStateful(provider, out, serverTx, method, callId);
    }

    // ------------------------------------------------------------------- session hooks

    /** Emits the start record once the callee answers. */
    public void onSessionAnswered(Response response, String callId) {
        if (sessions.isTracked(callId)) {
            return;
        }
        SessionTracker.Session session = sessions.start(callId,
                SipMessages.fromAor(response), SipMessages.toAor(response), Instant.now());
        charging.publish(ChargingEvent.start(session.callId(), session.from(), session.to(),
                session.startedAt()));
        applyMediaAnswer(response, callId);
    }

    private void endSession(String callId) {
        SessionTracker.Session session = sessions.end(callId);
        media.delete(callId);
        if (session == null) {
            return;
        }
        Instant now = Instant.now();
        charging.publish(ChargingEvent.end(session.callId(), session.from(), session.to(), now,
                now.getEpochSecond() - session.startedAt().getEpochSecond()));
    }

    // --------------------------------------------------------------------- media seam

    private void applyMediaOffer(Request request, String callId) throws Exception {
        String sdp = sdpOf(request);
        if (sdp == null) {
            return;
        }
        String rewritten = media.offer(callId, tagOf(request, true), sdp);
        if (rewritten != null && !rewritten.equals(sdp)) {
            request.setContent(rewritten, (ContentTypeHeader) request.getHeader(ContentTypeHeader.NAME));
        }
    }

    private void applyMediaAnswer(Response response, String callId) {
        try {
            String sdp = sdpOf(response);
            if (sdp == null) {
                return;
            }
            String rewritten = media.answer(callId, tagOf(response, true), tagOf(response, false), sdp);
            if (rewritten != null && !rewritten.equals(sdp)) {
                response.setContent(rewritten,
                        (ContentTypeHeader) response.getHeader(ContentTypeHeader.NAME));
            }
        } catch (Exception e) {
            log.error("Failed to apply media answer for callId={}", callId, e);
        }
    }

    private static String sdpOf(javax.sip.message.Message message) {
        ContentTypeHeader contentType = (ContentTypeHeader) message.getHeader(ContentTypeHeader.NAME);
        byte[] content = message.getRawContent();
        if (contentType == null || content == null
                || !SDP_SUBTYPE.equalsIgnoreCase(contentType.getContentSubType())) {
            return null;
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------ helpers

    private static boolean isInDialog(Request request) {
        ToHeader to = (ToHeader) request.getHeader(ToHeader.NAME);
        return to != null && to.getTag() != null;
    }

    private static boolean isDialogForming(String method) {
        return Request.INVITE.equals(method)
                || Request.SUBSCRIBE.equals(method)
                || Request.REFER.equals(method);
    }

    private static String tagOf(javax.sip.message.Message message, boolean from) {
        if (from) {
            FromHeader header = (FromHeader) message.getHeader(FromHeader.NAME);
            return header == null ? null : header.getTag();
        }
        ToHeader header = (ToHeader) message.getHeader(ToHeader.NAME);
        return header == null ? null : header.getTag();
    }
}
