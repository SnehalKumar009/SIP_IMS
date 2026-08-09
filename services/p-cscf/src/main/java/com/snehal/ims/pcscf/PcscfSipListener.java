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

    public PcscfSipListener(SipStackManager stack,
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
            capture(HepCaptureService.Direction.INBOUND, sourceHost, portOf(request),
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

            if (!rateLimiter.tryAcquire(sourceHost)) {
                metrics.recordDropped();
                log.warn("Rate limit exceeded for source {} ({} {})", sourceHost, method, callId);
                reject(serverTx, request, Response.SERVICE_UNAVAILABLE);
                return;
            }

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

        MaxForwardsHeader mf = out.getMaxForwards();
        if (mf == null) {
            out.setHeader(hf.createMaxForwardsHeader(70));
        } else if (mf.getMaxForwards() <= 0) {
            reject(serverTx, request, Response.TOO_MANY_HOPS);
            return;
        } else {
            mf.decrementMaxForwards();
        }

        // Insert the P-CSCF into the signaling path.
        out.addFirst(nextHopRoute());                               // outbound Route -> I-CSCF
        if (Request.REGISTER.equals(method)) {
            out.addFirst(hf.createHeader("Path",
                    "<sip:" + props.getSelfHost() + ":" + props.getSelfPort() + ";lr>"));
        }
        if (isDialogForming(method)) {
            out.addFirst(recordRoute());
        }
        ViaHeader via = hf.createViaHeader(props.getSelfHost(), props.getSelfPort(), transport(), null);
        via.setRPort();
        out.addFirst(via);

        ClientTransaction clientTx = provider.getNewClientTransaction(out);
        clientTx.setApplicationData(serverTx);

        capture(HepCaptureService.Direction.OUTBOUND, props.getSelfHost(), props.getSelfPort(),
                props.getNextHop().getHost(), props.getNextHop().getPort(), callId, out.toString());
        clientTx.sendRequest();
        metrics.recordSent(method);
    }

    private void forwardAck(SipProvider provider, Request request) throws Exception {
        HeaderFactory hf = stack.getHeaderFactory();
        Request out = (Request) request.clone();
        ViaHeader via = hf.createViaHeader(props.getSelfHost(), props.getSelfPort(), transport(), null);
        out.addFirst(via);
        out.addFirst(nextHopRoute());
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
            capture(HepCaptureService.Direction.INBOUND, props.getNextHop().getHost(),
                    props.getNextHop().getPort(), props.getSelfHost(), props.getSelfPort(),
                    callId, response.toString());

            // Strip the P-CSCF's own Via; the next Via belongs to the downstream UE.
            response.removeFirst(ViaHeader.NAME);
            if (response.getHeader(ViaHeader.NAME) == null) {
                // Response was addressed to this proxy itself; nothing to relay.
                return;
            }

            ClientTransaction clientTx = event.getClientTransaction();
            ServerTransaction serverTx =
                    clientTx != null ? (ServerTransaction) clientTx.getApplicationData() : null;

            capture(HepCaptureService.Direction.OUTBOUND, props.getSelfHost(), props.getSelfPort(),
                    topViaHost(response), portOf(response), callId, response.toString());

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
