package com.snehal.ims.pcscf;

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
import javax.sip.header.CallIdHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.RecordRouteHeader;
import javax.sip.header.RouteHeader;
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
 * P-CSCF stateful SIP proxy. Auto-attached to the shared {@link SipStackManager}
 * because it is the single {@link SipListener} bean in the context.
 *
 * <p>For every request it rate-limits by source, mirrors the message to Homer,
 * inserts the P-CSCF into the signaling path (Via + Record-Route, plus Path on
 * REGISTER), decrements Max-Forwards, and forwards to the upstream I-CSCF. Responses
 * are matched back to the originating server transaction and relayed downstream.</p>
 */
@Component
public class PcscfSipListener implements SipListener {

    private static final Logger log = LoggerFactory.getLogger(PcscfSipListener.class);

    private final SipStackManager stack;
    private final HepCaptureService hep;
    private final SipMetrics metrics;
    private final SipRateLimiter rateLimiter;
    private final PcscfProperties props;

    // @Lazy breaks the pcscfSipListener <-> sipStackManager cycle: the manager attaches
    // this listener during its own @PostConstruct, and the listener only needs the manager's
    // factories at request time, so a lazy proxy is sufficient here.
    public PcscfSipListener(@Lazy SipStackManager stack,
                            HepCaptureService hep,
                            SipMetrics metrics,
                            SipRateLimiter rateLimiter,
                            PcscfProperties props) {
        this.stack = stack;
        this.hep = hep;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
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
            String sourceHost = topViaHost(request);
            log.debug("TRACE IN  <- {} callId={} src={}:{} ruri={}",
                    method, callId, sourceHost, portOf(request), request.getRequestURI());
            log.debug("TRACE IN  <- full message:\n{}", request);
            capture(HepCaptureService.Direction.INBOUND, sourceHost, portOf(request),
                    props.getSelfHost(), props.getSelfPort(), callId, request.toString());

            // ACK is end-to-end within an established dialog: forward statelessly.
            if (Request.ACK.equals(method)) {
                log.debug("TRACE     · ACK is end-to-end, forwarding statelessly");
                forwardAck(provider, request);
                return;
            }

            ServerTransaction serverTx = event.getServerTransaction();
            if (serverTx == null) {
                serverTx = provider.getNewServerTransaction(request);
                log.debug("TRACE     · created new server transaction branch={}", serverTx.getBranchId());
            } else {
                log.debug("TRACE     · reusing server transaction branch={}", serverTx.getBranchId());
            }

            if (!rateLimiter.tryAcquire(sourceHost)) {
                metrics.recordDropped();
                log.warn("Rate limit exceeded for source {} ({} {})", sourceHost, method, callId);
                reject(serverTx, request, Response.SERVICE_UNAVAILABLE);
                return;
            }

            log.debug("TRACE     · rate-limit passed for src={}", sourceHost);
            forwardRequest(provider, request, serverTx, method, callId);
        } catch (Exception e) {
            metrics.recordDropped();
            log.error("Failed to process {} request", method, e);
        } finally {
            metrics.stopTimer(sample);
            MDC.clear();
        }
    }

    private void forwardRequest(SipProvider provider, Request request,
                                ServerTransaction serverTx, String method, String callId) throws Exception {
        HeaderFactory hf = stack.getHeaderFactory();
        Request out = (Request) request.clone();

        MaxForwardsHeader mf = (MaxForwardsHeader) out.getHeader(MaxForwardsHeader.NAME);
        if (mf == null) {
            out.setHeader(hf.createMaxForwardsHeader(70));
            log.debug("TRACE     · no Max-Forwards present, set to 70");
        } else if (mf.getMaxForwards() <= 0) {
            log.debug("TRACE     · Max-Forwards exhausted, rejecting 483");
            reject(serverTx, request, Response.TOO_MANY_HOPS);
            return;
        } else {
            mf.decrementMaxForwards();
            log.debug("TRACE     · Max-Forwards decremented to {}", mf.getMaxForwards());
        }

        // Insert the P-CSCF into the signaling path.
        RouteHeader nextHop = nextHopRoute();
        out.addFirst(nextHop);                                      // outbound Route -> I-CSCF
        log.debug("TRACE     · added Route -> {}", nextHop.getAddress().getURI());
        if (Request.REGISTER.equals(method)) {
            String path = "<sip:" + props.getSelfHost() + ":" + props.getSelfPort() + ";lr>";
            out.addFirst(hf.createHeader("Path", path));
            log.debug("TRACE     · added Path {} (return route for terminating requests)", path);
        }
        if (isDialogForming(method)) {
            out.addFirst(recordRoute());
            log.debug("TRACE     · added Record-Route (dialog-forming {})", method);
        }
        ViaHeader via = hf.createViaHeader(props.getSelfHost(), props.getSelfPort(), transport(), null);
        via.setRPort();
        out.addFirst(via);
        log.debug("TRACE     · added own Via {}:{}/{};rport",
                props.getSelfHost(), props.getSelfPort(), transport());

        ClientTransaction clientTx = provider.getNewClientTransaction(out);
        clientTx.setApplicationData(serverTx);

        capture(HepCaptureService.Direction.OUTBOUND, props.getSelfHost(), props.getSelfPort(),
                props.getNextHop().getHost(), props.getNextHop().getPort(), callId, out.toString());
        clientTx.sendRequest();
        metrics.recordSent(method);
        log.debug("TRACE OUT -> {} callId={} dst={}:{} branch={}",
                method, callId, props.getNextHop().getHost(), props.getNextHop().getPort(),
                clientTx.getBranchId());
        log.debug("TRACE OUT -> full message:\n{}", out);
    }

    private void forwardAck(SipProvider provider, Request request) throws Exception {
        HeaderFactory hf = stack.getHeaderFactory();
        Request out = (Request) request.clone();
        ViaHeader via = hf.createViaHeader(props.getSelfHost(), props.getSelfPort(), transport(), null);
        out.addFirst(via);
        out.addFirst(nextHopRoute());
        provider.sendRequest(out);
        metrics.recordSent(Request.ACK);
        log.debug("TRACE OUT -> ACK callId={} dst={}:{}", callId(request),
                props.getNextHop().getHost(), props.getNextHop().getPort());
    }

    // ----------------------------------------------------------------- responses

    @Override
    public void processResponse(ResponseEvent event) {
        Response response = event.getResponse();
        int status = response.getStatusCode();
        String callId = callId(response);
        MDC.put("callId", callId);
        try {
            log.debug("TRACE IN  <- {} {} callId={} from upstream {}:{}",
                    status, response.getReasonPhrase(), callId,
                    props.getNextHop().getHost(), props.getNextHop().getPort());
            log.debug("TRACE IN  <- full message:\n{}", response);
            capture(HepCaptureService.Direction.INBOUND, props.getNextHop().getHost(),
                    props.getNextHop().getPort(), props.getSelfHost(), props.getSelfPort(),
                    callId, response.toString());

            // Strip the P-CSCF's own Via; the next Via belongs to the downstream UE.
            response.removeFirst(ViaHeader.NAME);
            if (response.getHeader(ViaHeader.NAME) == null) {
                // Response was addressed to this proxy itself; nothing to relay.
                log.debug("TRACE     · no Via left after popping our own — response was for us, not relaying");
                return;
            }
            log.debug("TRACE     · popped own Via, next Via is {}:{}",
                    topViaHost(response), portOf(response));

            ClientTransaction clientTx = event.getClientTransaction();
            ServerTransaction serverTx =
                    clientTx != null ? (ServerTransaction) clientTx.getApplicationData() : null;

            capture(HepCaptureService.Direction.OUTBOUND, props.getSelfHost(), props.getSelfPort(),
                    topViaHost(response), portOf(response), callId, response.toString());

            if (serverTx != null) {
                serverTx.sendResponse(response);
                log.debug("TRACE OUT -> {} callId={} via server tx branch={}",
                        status, callId, serverTx.getBranchId());
            } else {
                ((SipProvider) event.getSource()).sendResponse(response);
                log.debug("TRACE OUT -> {} callId={} statelessly (no server tx)", status, callId);
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
        log.warn("TRACE !!! transaction timeout ({}) — upstream did not answer", event.getTimeout());
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
        // no-op: P-CSCF is dialog-stateless in Phase 1
    }

    // ------------------------------------------------------------------- helpers

    private void reject(ServerTransaction serverTx, Request request, int status) throws Exception {
        MessageFactory mf = stack.getMessageFactory();
        Response response = mf.createResponse(status, request);
        serverTx.sendResponse(response);
        capture(HepCaptureService.Direction.OUTBOUND, props.getSelfHost(), props.getSelfPort(),
                topViaHost(request), portOf(request), callId(request), response.toString());
        metrics.recordSent(String.valueOf(status));
    }

    private RecordRouteHeader recordRoute() throws Exception {
        AddressFactory af = stack.getAddressFactory();
        SipURI uri = af.createSipURI(null, props.getSelfHost());
        uri.setPort(props.getSelfPort());
        uri.setLrParam();
        uri.setTransportParam(transport());
        Address address = af.createAddress(uri);
        return stack.getHeaderFactory().createRecordRouteHeader(address);
    }

    private RouteHeader nextHopRoute() throws Exception {
        AddressFactory af = stack.getAddressFactory();
        PcscfProperties.NextHop nh = props.getNextHop();
        SipURI uri = af.createSipURI(null, nh.getHost());
        uri.setPort(nh.getPort());
        uri.setLrParam();
        uri.setTransportParam(nh.getTransport());
        Address address = af.createAddress(uri);
        return stack.getHeaderFactory().createRouteHeader(address);
    }

    private void capture(HepCaptureService.Direction direction, String srcHost, int srcPort,
                         String dstHost, int dstPort, String correlationId, String payload) {
        hep.capture(direction, isUdp(), resolve(srcHost), srcPort, resolve(dstHost), dstPort,
                correlationId, payload);
    }

    private boolean isDialogForming(String method) {
        return Request.INVITE.equals(method)
                || Request.SUBSCRIBE.equals(method)
                || Request.REFER.equals(method);
    }

    private boolean isUdp() {
        return "udp".equalsIgnoreCase(transport());
    }

    private String transport() {
        return stack.getListeningPoint().getTransport();
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
}
