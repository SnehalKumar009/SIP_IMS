package com.snehal.ims.scscf.sip;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.snehal.ims.telecom.metrics.SipMetrics;
import com.snehal.ims.telecom.sip.SipStackManager;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import javax.sip.ClientTransaction;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.header.CSeqHeader;
import javax.sip.header.Header;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * S-CSCF SIP entry point. Auto-attached to the shared {@link SipStackManager} because it
 * is the single {@link SipListener} bean.
 *
 * <p>Deliberately thin: it owns transaction plumbing, admission control and response
 * relay, and delegates the IMS logic to {@link RegistrarHandler} and
 * {@link SessionHandler}.</p>
 */
@Component
public class ScscfSipListener implements SipListener {

    private static final Logger log = LoggerFactory.getLogger(ScscfSipListener.class);

    private final ProxyForwarder proxy;
    private final RegistrarHandler registrar;
    private final SessionHandler sessions;
    private final DrainCoordinator drain;
    private final SipMetrics metrics;

    /**
     * INVITE client transactions awaiting a final response, so a CANCEL can be proxied
     * onto the right upstream transaction instead of being blindly forwarded.
     */
    private final Cache<String, ClientTransaction> pendingInvites = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(3))
            .maximumSize(50_000)
            .build();

    public ScscfSipListener(ProxyForwarder proxy,
                            RegistrarHandler registrar,
                            SessionHandler sessions,
                            DrainCoordinator drain,
                            SipMetrics metrics) {
        this.proxy = proxy;
        this.registrar = registrar;
        this.sessions = sessions;
        this.drain = drain;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ requests

    @Override
    public void processRequest(RequestEvent event) {
        Request request = event.getRequest();
        String method = request.getMethod();
        String callId = SipMessages.callId(request);
        MDC.put("callId", callId);
        MDC.put("sipMethod", method);
        metrics.recordReceived(method);
        Timer.Sample sample = metrics.startTimer();
        try {
            SipProvider provider = (SipProvider) event.getSource();
            proxy.captureInbound(request, callId);

            // ACK is end-to-end within an established dialog: forward statelessly.
            if (Request.ACK.equals(method)) {
                log.debug("TRACE     · ACK is end-to-end, forwarding statelessly");
                forwardAck(provider, request, callId);
                return;
            }

            ServerTransaction serverTx = event.getServerTransaction();
            if (serverTx == null) {
                serverTx = provider.getNewServerTransaction(request);
            }

            if (Request.CANCEL.equals(method)) {
                log.debug("TRACE     · CANCEL — proxying onto the pending INVITE transaction");
                cancel(provider, request, serverTx, callId);
                return;
            }

            if (proxy.isLooping(request)) {
                log.warn("TRACE !!! loop detected for {} {} — too many of our own Via headers", method, callId);
                proxy.reject(serverTx, request, Response.LOOP_DETECTED);
                return;
            }

            // While draining, established dialogs keep working; new ones go elsewhere.
            if (drain.isDraining() && isOutOfDialog(request)) {
                log.debug("TRACE     · draining, refusing new out-of-dialog {} with 503", method);
                Header retryAfter = proxy.stack().getHeaderFactory()
                        .createHeader("Retry-After", String.valueOf(drain.retryAfterSeconds()));
                proxy.respond(serverTx, request, Response.SERVICE_UNAVAILABLE, retryAfter);
                return;
            }

            if (Request.REGISTER.equals(method)) {
                log.debug("TRACE     · dispatching to RegistrarHandler");
                registrar.handle(request, serverTx);
                return;
            }

            log.debug("TRACE     · dispatching to SessionHandler");
            ClientTransaction clientTx = sessions.handle(provider, request, serverTx, method, callId);
            if (clientTx != null && Request.INVITE.equals(method)) {
                pendingInvites.put(callId, clientTx);
                log.debug("TRACE     · remembered pending INVITE for callId={}", callId);
            }
        } catch (Exception e) {
            metrics.recordDropped();
            log.error("Failed to process {} request", method, e);
        } finally {
            metrics.stopTimer(sample);
            MDC.clear();
        }
    }

    private void forwardAck(SipProvider provider, Request request, String callId) throws Exception {
        Request out = (Request) request.clone();
        proxy.popOwnRoute(out);
        proxy.addOwnVia(out);
        proxy.sendStateless(provider, out, Request.ACK, callId);
    }

    /**
     * Proxies a CANCEL onto the INVITE it refers to. Forwarding the CANCEL as an ordinary
     * request would not match the upstream transaction and the callee would keep ringing.
     */
    private void cancel(SipProvider provider, Request request, ServerTransaction serverTx,
                        String callId) throws Exception {
        proxy.respond(serverTx, request, Response.OK);

        ClientTransaction invite = pendingInvites.getIfPresent(callId);
        if (invite == null) {
            log.debug("CANCEL for unknown or already answered INVITE {}", callId);
            return;
        }
        try {
            Request cancel = invite.createCancel();
            ClientTransaction cancelTx = provider.getNewClientTransaction(cancel);
            proxy.captureOutbound(cancel, callId);
            cancelTx.sendRequest();
            metrics.recordSent(Request.CANCEL);
        } catch (Exception e) {
            log.warn("Could not cancel INVITE {}", callId, e);
        }
    }

    // ----------------------------------------------------------------- responses

    @Override
    public void processResponse(ResponseEvent event) {
        Response response = event.getResponse();
        int status = response.getStatusCode();
        String callId = SipMessages.callId(response);
        MDC.put("callId", callId);
        try {
            proxy.captureInbound(response, callId);

            if (isFinal(status) && Request.INVITE.equals(cseqMethod(response))) {
                pendingInvites.invalidate(callId);
                if (status == Response.OK) {
                    sessions.onSessionAnswered(response, callId);
                }
            }

            // Strip the S-CSCF's own Via; the next Via belongs to the downstream hop.
            response.removeFirst(ViaHeader.NAME);
            if (response.getHeader(ViaHeader.NAME) == null) {
                // Response was addressed to this proxy itself; nothing to relay.
                log.debug("TRACE     · no Via left after popping our own — not relaying");
                return;
            }
            log.debug("TRACE     · popped own Via, next Via is {}:{}",
                    SipMessages.topViaHost(response), SipMessages.topViaPort(response));

            proxy.captureOutboundResponse(response, response);

            ClientTransaction clientTx = event.getClientTransaction();
            ServerTransaction serverTx =
                    clientTx != null ? (ServerTransaction) clientTx.getApplicationData() : null;
            if (serverTx != null) {
                serverTx.sendResponse(response);
                log.debug("TRACE     · relayed on server transaction branch={}", serverTx.getBranchId());
            } else {
                ((SipProvider) event.getSource()).sendResponse(response);
                log.debug("TRACE     · relayed statelessly (no server transaction)");
            }
            metrics.recordSent(String.valueOf(status));
        } catch (Exception e) {
            log.error("Failed to relay {} response", status, e);
        } finally {
            MDC.clear();
        }
    }

    // -------------------------------------------------------------------- errors

    @Override
    public void processTimeout(TimeoutEvent event) {
        log.warn("TRACE !!! transaction timeout ({}) — downstream did not answer", event.getTimeout());
    }

    @Override
    public void processIOException(IOExceptionEvent event) {
        log.error("SIP I/O exception on {}:{}/{}", event.getHost(), event.getPort(), event.getTransport());
    }

    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent event) {
        // no-op: transaction state is owned by the stack
    }

    @Override
    public void processDialogTerminated(DialogTerminatedEvent event) {
        // no-op: session state is keyed by Call-ID in SessionTracker, not by dialog
    }

    // ------------------------------------------------------------------- helpers

    private static boolean isOutOfDialog(Request request) {
        ToHeader to = (ToHeader) request.getHeader(ToHeader.NAME);
        return to == null || to.getTag() == null;
    }

    private static boolean isFinal(int status) {
        return status >= 200;
    }

    private static String cseqMethod(Response response) {
        CSeqHeader header = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        return header == null ? null : header.getMethod();
    }
}
