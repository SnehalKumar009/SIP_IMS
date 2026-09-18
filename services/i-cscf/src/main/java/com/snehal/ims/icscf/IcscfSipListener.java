package com.snehal.ims.icscf;

import com.snehal.ims.hss.grpc.AuthorizationType;
import com.snehal.ims.hss.grpc.LocationInfoAnswer;
import com.snehal.ims.hss.grpc.UserAuthorizationAnswer;
import com.snehal.ims.icscf.ScscfSelector.ScscfTarget;
import com.snehal.ims.telecom.hep.HepCaptureService;
import com.snehal.ims.telecom.metrics.SipMetrics;
import com.snehal.ims.telecom.sip.SipStackManager;
import io.micrometer.core.instrument.Timer;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
import javax.sip.header.AuthorizationHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * I-CSCF transaction-stateful SIP proxy. Auto-attached to the shared
 * {@link SipStackManager} because it is the single {@link SipListener} bean.
 *
 * <p>On REGISTER it runs a Cx User-Authorization (UAR) against the HSS to authorize
 * the user and either reuse the assigned S-CSCF or select one from the returned
 * capabilities. On terminating requests it runs a Location-Info (LIR) to find the
 * serving S-CSCF. It then inserts itself into the Via path and forwards to that
 * S-CSCF. Unlike the P-CSCF it does not Record-Route: the I-CSCF stays out of the
 * subsequent in-dialog signaling path.</p>
 */
@Component
public class IcscfSipListener implements SipListener {

    private static final Logger log = LoggerFactory.getLogger(IcscfSipListener.class);

    // ---- Cx result codes mirrored from the HSS contract (TS 29.229) ----
    private static final int DIAMETER_SUCCESS = 2001;
    private static final int DIAMETER_FIRST_REGISTRATION = 2001;
    private static final int DIAMETER_SUBSEQUENT_REGISTRATION = 2002;
    private static final int DIAMETER_UNREGISTERED_SERVICE = 2003;
    private static final int DIAMETER_ERROR_USER_UNKNOWN = 5001;
    private static final int DIAMETER_ERROR_IDENTITIES_DONT_MATCH = 5002;
    private static final int DIAMETER_ERROR_IDENTITY_NOT_REGISTERED = 5003;

    private final SipStackManager stack;
    private final HepCaptureService hep;
    private final SipMetrics metrics;
    private final CxClient cx;
    private final ScscfSelector selector;
    private final IcscfProperties props;

    // @Lazy breaks the icscfSipListener <-> sipStackManager cycle: the manager attaches
    // this listener during its own @PostConstruct, and the listener only needs the manager's
    // factories at request time, so a lazy proxy is sufficient here.
    public IcscfSipListener(@Lazy SipStackManager stack,
                            HepCaptureService hep,
                            SipMetrics metrics,
                            CxClient cx,
                            ScscfSelector selector,
                            IcscfProperties props) {
        this.stack = stack;
        this.hep = hep;
        this.metrics = metrics;
        this.cx = cx;
        this.selector = selector;
        this.props = props;
    }

    // ------------------------------------------------------------------ requests

    @Override
    public void processRequest(RequestEvent event) {
        Request request = event.getRequest();
        String method = request.getMethod();
        String callId = callId(request);
        MDC.put("callId", callId);
        MDC.put("sipMethod", method);
        metrics.recordReceived(method);
        Timer.Sample sample = metrics.startTimer();
        try {
            SipProvider provider = (SipProvider) event.getSource();
            capture(HepCaptureService.Direction.INBOUND, topViaHost(request), portOf(request),
                    props.getSelfHost(), props.getSelfPort(), callId, request.toString());

            // ACK is end-to-end within an established dialog: forward statelessly.
            if (Request.ACK.equals(method)) {
                forwardAck(provider, request);
                return;
            }

            ServerTransaction serverTx = event.getServerTransaction();
            if (serverTx == null) {
                serverTx = provider.getNewServerTransaction(request);
            }

            ScscfTarget target = resolveScscf(request, method, serverTx, callId);
            if (target == null) {
                // resolveScscf already rejected the transaction.
                return;
            }
            forwardRequest(provider, request, serverTx, method, callId, target);
        } catch (Exception e) {
            metrics.recordDropped();
            log.error("Failed to process {} request", method, e);
        } finally {
            metrics.stopTimer(sample);
            MDC.clear();
        }
    }

    /**
     * Interrogates the HSS (UAR for REGISTER, LIR otherwise) and returns the resolved
     * S-CSCF, or {@code null} after rejecting the transaction on a Cx error.
     */
    private ScscfTarget resolveScscf(Request request, String method,
                                     ServerTransaction serverTx, String callId) throws Exception {
        if (Request.REGISTER.equals(method)) {
            String impu = registeringImpu(request);
            String impi = privateIdentity(request, impu);
            UserAuthorizationAnswer uaa = cx.userAuthorization(
                    impu, impi, props.getVisitedNetwork(), AuthorizationType.AUTHORIZATION_TYPE_REGISTRATION);
            Integer sipError = cxErrorToSip(uaa.getResultCode(), uaa.getExperimentalResultCode());
            if (sipError != null) {
                log.info("UAR rejected impu={} result={} experimental={}", impu,
                        uaa.getResultCode(), uaa.getExperimentalResultCode());
                reject(serverTx, request, sipError);
                return null;
            }
            log.debug("UAR ok impu={} server={} (exp={})", impu, uaa.getServerName(),
                    uaa.getExperimentalResultCode());
            return selector.select(blankToNull(uaa.getServerName()));
        }

        String impu = terminatingImpu(request);
        LocationInfoAnswer lia = cx.locationInfo(impu, false);
        Integer sipError = cxErrorToSip(lia.getResultCode(), lia.getExperimentalResultCode());
        if (sipError != null) {
            log.info("LIR rejected impu={} result={} experimental={}", impu,
                    lia.getResultCode(), lia.getExperimentalResultCode());
            reject(serverTx, request, sipError);
            return null;
        }
        log.debug("LIR ok impu={} server={} (exp={})", impu, lia.getServerName(),
                lia.getExperimentalResultCode());
        return selector.select(blankToNull(lia.getServerName()));
    }

    private void forwardRequest(SipProvider provider, Request request, ServerTransaction serverTx,
                                String method, String callId, ScscfTarget target) throws Exception {
        HeaderFactory hf = stack.getHeaderFactory();
        Request out = (Request) request.clone();

        MaxForwardsHeader mf = (MaxForwardsHeader) out.getHeader(MaxForwardsHeader.NAME);
        if (mf == null) {
            out.setHeader(hf.createMaxForwardsHeader(70));
        } else if (mf.getMaxForwards() <= 0) {
            reject(serverTx, request, Response.TOO_MANY_HOPS);
            return;
        } else {
            mf.decrementMaxForwards();
        }

        // Route toward the resolved S-CSCF, then our own Via on top.
        out.addFirst(scscfRoute(target));
        ViaHeader via = hf.createViaHeader(props.getSelfHost(), props.getSelfPort(), transport(), null);
        via.setRPort();
        out.addFirst(via);

        ClientTransaction clientTx = provider.getNewClientTransaction(out);
        clientTx.setApplicationData(new TxContext(serverTx, target));

        capture(HepCaptureService.Direction.OUTBOUND, props.getSelfHost(), props.getSelfPort(),
                target.host(), target.port(), callId, out.toString());
        clientTx.sendRequest();
        metrics.recordSent(method);
    }

    private void forwardAck(SipProvider provider, Request request) throws Exception {
        HeaderFactory hf = stack.getHeaderFactory();
        Request out = (Request) request.clone();
        ViaHeader via = hf.createViaHeader(props.getSelfHost(), props.getSelfPort(), transport(), null);
        out.addFirst(via);
        provider.sendRequest(out);
        metrics.recordSent(Request.ACK);
    }

    // ----------------------------------------------------------------- responses

    @Override
    public void processResponse(ResponseEvent event) {
        Response response = event.getResponse();
        int status = response.getStatusCode();
        String callId = callId(response);
        MDC.put("callId", callId);
        try {
            ClientTransaction clientTx = event.getClientTransaction();
            TxContext ctx = clientTx != null ? (TxContext) clientTx.getApplicationData() : null;
            ScscfTarget upstream = ctx != null ? ctx.upstream() : null;

            capture(HepCaptureService.Direction.INBOUND,
                    upstream != null ? upstream.host() : "0.0.0.0",
                    upstream != null ? upstream.port() : 5060,
                    props.getSelfHost(), props.getSelfPort(), callId, response.toString());

            // Strip the I-CSCF's own Via; the next Via belongs to the downstream P-CSCF/UE.
            response.removeFirst(ViaHeader.NAME);
            if (response.getHeader(ViaHeader.NAME) == null) {
                // Response was addressed to this proxy itself; nothing to relay.
                return;
            }

            capture(HepCaptureService.Direction.OUTBOUND, props.getSelfHost(), props.getSelfPort(),
                    topViaHost(response), portOf(response), callId, response.toString());

            ServerTransaction serverTx = ctx != null ? ctx.serverTx() : null;
            if (serverTx != null) {
                serverTx.sendResponse(response);
            } else {
                ((SipProvider) event.getSource()).sendResponse(response);
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
        log.warn("Transaction timeout ({})", event.getTimeout());
    }

    @Override
    public void processIOException(IOExceptionEvent event) {
        log.error("SIP I/O exception on {}:{}/{}", event.getHost(), event.getPort(), event.getTransport());
    }

    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent event) {
        // no-op: stateful proxy holds no per-transaction resources to release here
    }

    @Override
    public void processDialogTerminated(DialogTerminatedEvent event) {
        // no-op: the I-CSCF is dialog-stateless
    }

    // ------------------------------------------------------------------- helpers

    /** Maps a Cx error result onto a SIP status, or {@code null} when the answer is a success. */
    private static Integer cxErrorToSip(int resultCode, int experimentalResultCode) {
        int error = resultCode >= 5000 ? resultCode
                : (resultCode == 0 && experimentalResultCode >= 5000 ? experimentalResultCode : 0);
        if (error == 0) {
            return null;
        }
        return switch (error) {
            case DIAMETER_ERROR_USER_UNKNOWN -> Response.NOT_FOUND;
            case DIAMETER_ERROR_IDENTITIES_DONT_MATCH -> Response.FORBIDDEN;
            case DIAMETER_ERROR_IDENTITY_NOT_REGISTERED -> Response.TEMPORARILY_UNAVAILABLE;
            default -> Response.SERVER_INTERNAL_ERROR;
        };
    }

    private void reject(ServerTransaction serverTx, Request request, int status) throws Exception {
        MessageFactory mf = stack.getMessageFactory();
        Response response = mf.createResponse(status, request);
        serverTx.sendResponse(response);
        capture(HepCaptureService.Direction.OUTBOUND, props.getSelfHost(), props.getSelfPort(),
                topViaHost(request), portOf(request), callId(request), response.toString());
        metrics.recordSent(String.valueOf(status));
    }

    private RouteHeader scscfRoute(ScscfTarget target) throws Exception {
        AddressFactory af = stack.getAddressFactory();
        SipURI uri = af.createSipURI(null, target.host());
        uri.setPort(target.port());
        uri.setLrParam();
        uri.setTransportParam(target.transport());
        Address address = af.createAddress(uri);
        return stack.getHeaderFactory().createRouteHeader(address);
    }

    /** Public identity of the registering user: the To-header AOR. */
    private static String registeringImpu(Request request) {
        ToHeader to = (ToHeader) request.getHeader(ToHeader.NAME);
        return to != null ? stripUriParams(to.getAddress().getURI().toString()) : null;
    }

    /** Public identity of a terminating request: the Request-URI. */
    private static String terminatingImpu(Request request) {
        URI uri = request.getRequestURI();
        return uri != null ? stripUriParams(uri.toString()) : null;
    }

    /** Private identity: the Authorization username when present, else derived from the IMPU. */
    private static String privateIdentity(Request request, String impu) {
        AuthorizationHeader auth = (AuthorizationHeader) request.getHeader(AuthorizationHeader.NAME);
        if (auth != null && auth.getUsername() != null && !auth.getUsername().isBlank()) {
            return auth.getUsername();
        }
        if (impu == null) {
            return null;
        }
        int scheme = impu.indexOf(':');
        return scheme >= 0 ? impu.substring(scheme + 1) : impu;
    }

    private static String stripUriParams(String uri) {
        int semi = uri.indexOf(';');
        return semi >= 0 ? uri.substring(0, semi) : uri;
    }

    private void capture(HepCaptureService.Direction direction, String srcHost, int srcPort,
                         String dstHost, int dstPort, String correlationId, String payload) {
        hep.capture(direction, isUdp(), resolve(srcHost), srcPort, resolve(dstHost), dstPort,
                correlationId, payload);
    }

    private boolean isUdp() {
        return "udp".equalsIgnoreCase(transport());
    }

    private String transport() {
        return stack.getListeningPoint().getTransport();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String callId(javax.sip.message.Message message) {
        CallIdHeader header = (CallIdHeader) message.getHeader(CallIdHeader.NAME);
        return header != null ? header.getCallId() : null;
    }

    private static String topViaHost(javax.sip.message.Message message) {
        ViaHeader via = (ViaHeader) message.getHeader(ViaHeader.NAME);
        if (via == null) {
            return "0.0.0.0";
        }
        String received = via.getReceived();
        return received != null ? received : via.getHost();
    }

    private static int portOf(javax.sip.message.Message message) {
        ViaHeader via = (ViaHeader) message.getHeader(ViaHeader.NAME);
        if (via == null) {
            return 5060;
        }
        int rport = via.getRPort();
        if (rport > 0) {
            return rport;
        }
        return via.getPort() > 0 ? via.getPort() : 5060;
    }

    private static InetAddress resolve(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return InetAddress.getLoopbackAddress();
        }
    }

    /** Per-client-transaction context: the originating server tx and the chosen S-CSCF. */
    private record TxContext(ServerTransaction serverTx, ScscfTarget upstream) {
    }
}
