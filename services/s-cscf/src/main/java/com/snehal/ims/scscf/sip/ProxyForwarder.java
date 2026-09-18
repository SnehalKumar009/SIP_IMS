package com.snehal.ims.scscf.sip;

import com.snehal.ims.scscf.ScscfMetrics;
import com.snehal.ims.scscf.ScscfProperties;
import com.snehal.ims.telecom.hep.HepCaptureService;
import com.snehal.ims.telecom.metrics.SipMetrics;
import com.snehal.ims.telecom.sip.SipStackManager;
import java.util.Iterator;
import java.util.List;
import javax.sip.ClientTransaction;
import javax.sip.ServerTransaction;
import javax.sip.SipProvider;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.RecordRouteHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Proxy mechanics shared by the registrar and the session handlers: loop detection,
 * Max-Forwards, loose-route processing, Via/Record-Route insertion, Homer capture and
 * transaction-stateful send.
 */
@Component
public class ProxyForwarder {

    private static final Logger log = LoggerFactory.getLogger(ProxyForwarder.class);

    private final SipStackManager stack;
    private final HepCaptureService hep;
    private final SipMetrics sipMetrics;
    private final ScscfMetrics scscfMetrics;
    private final ScscfProperties props;

    // @Lazy breaks the listener <-> sipStackManager cycle: the manager attaches the
    // listener during its own @PostConstruct and the factories are only needed at
    // request time.
    public ProxyForwarder(@Lazy SipStackManager stack,
                          HepCaptureService hep,
                          SipMetrics sipMetrics,
                          ScscfMetrics scscfMetrics,
                          ScscfProperties props) {
        this.stack = stack;
        this.hep = hep;
        this.sipMetrics = sipMetrics;
        this.scscfMetrics = scscfMetrics;
        this.props = props;
    }

    // ---------------------------------------------------------------- inspection

    /**
     * Detects a request that has traversed this proxy more times than an IMS spiral
     * legitimately requires. Counting our own Via headers catches loops that
     * Max-Forwards would only stop 70 hops later.
     */
    @SuppressWarnings("unchecked")
    public boolean isLooping(Request request) {
        int seen = 0;
        Iterator<ViaHeader> vias = (Iterator<ViaHeader>) request.getHeaders(ViaHeader.NAME);
        while (vias != null && vias.hasNext()) {
            ViaHeader via = vias.next();
            if (isOwnHostPort(via.getHost(), via.getPort())) {
                seen++;
            }
        }
        if (seen > props.getRouting().getMaxSpirals()) {
            scscfMetrics.recordLoopDetected();
            return true;
        }
        return false;
    }

    /** {@code true} when the top Route header addresses this S-CSCF. */
    public boolean topRouteIsSelf(Request request) {
        RouteHeader route = (RouteHeader) request.getHeader(RouteHeader.NAME);
        return route != null && isOwnUri(route.getAddress().getURI());
    }

    /** Value of a parameter on the top Route header, or {@code null}. */
    public String topRouteParameter(Request request, String name) {
        RouteHeader route = (RouteHeader) request.getHeader(RouteHeader.NAME);
        if (route == null || !(route.getAddress().getURI() instanceof SipURI uri)) {
            return null;
        }
        return uri.getParameter(name);
    }

    public boolean isOwnUri(URI uri) {
        return uri instanceof SipURI sipUri
                && isOwnHostPort(sipUri.getHost(), sipUri.getPort());
    }

    private boolean isOwnHostPort(String host, int port) {
        if (host == null) {
            return false;
        }
        int effectivePort = port > 0 ? port : 5060;
        return host.equalsIgnoreCase(props.getSelfHost()) && effectivePort == props.getSelfPort();
    }

    // ------------------------------------------------------------- request shaping

    /**
     * Removes this proxy's own Route header (RFC 3261 §16.4). Leaving it in place would
     * make the request spiral back through us on every in-dialog hop.
     */
    public void popOwnRoute(Request request) {
        if (topRouteIsSelf(request)) {
            request.removeFirst(RouteHeader.NAME);
        }
    }

    /** @return {@code false} when Max-Forwards is exhausted and the request must get 483. */
    public boolean decrementMaxForwards(Request request) throws Exception {
        MaxForwardsHeader header = (MaxForwardsHeader) request.getHeader(MaxForwardsHeader.NAME);
        if (header == null) {
            request.setHeader(stack.getHeaderFactory()
                    .createMaxForwardsHeader(props.getRouting().getDefaultMaxForwards()));
            return true;
        }
        if (header.getMaxForwards() <= 0) {
            return false;
        }
        header.decrementMaxForwards();
        return true;
    }

    public void addOwnVia(Request request) throws Exception {
        ViaHeader via = stack.getHeaderFactory()
                .createViaHeader(props.getSelfHost(), props.getSelfPort(), transport(), null);
        via.setRPort();
        request.addFirst(via);
    }

    /**
     * Keeps the S-CSCF in the route set of a dialog. Unlike the I-CSCF the S-CSCF must
     * stay on the path: it owns session state, charging boundaries and media control for
     * the life of the call.
     */
    public void addRecordRoute(Request request) throws Exception {
        AddressFactory af = stack.getAddressFactory();
        SipURI uri = af.createSipURI(null, props.getSelfHost());
        uri.setPort(props.getSelfPort());
        uri.setLrParam();
        uri.setTransportParam(transport());
        RecordRouteHeader header = stack.getHeaderFactory()
                .createRecordRouteHeader(af.createAddress(uri));
        request.addFirst(header);
    }

    /**
     * Replays a stored Path set as the Route set of an outgoing request, so a terminating
     * request retraces the exact chain of proxies the UE registered through.
     */
    public void applyPathAsRoute(Request request, List<String> path) throws Exception {
        HeaderFactory hf = stack.getHeaderFactory();
        for (int i = path.size() - 1; i >= 0; i--) {
            Header route = hf.createHeader(RouteHeader.NAME, path.get(i));
            request.addFirst(route);
        }
    }

    public RouteHeader route(String host, int port, String transport) throws Exception {
        AddressFactory af = stack.getAddressFactory();
        SipURI uri = af.createSipURI(null, host);
        uri.setPort(port);
        uri.setLrParam();
        uri.setTransportParam(transport);
        Address address = af.createAddress(uri);
        return stack.getHeaderFactory().createRouteHeader(address);
    }

    /** {@code <sip:self:port;lr>} — used for Service-Route and Path. */
    public SipURI ownUri() throws Exception {
        SipURI uri = stack.getAddressFactory().createSipURI(null, props.getSelfHost());
        uri.setPort(props.getSelfPort());
        uri.setLrParam();
        return uri;
    }

    // --------------------------------------------------------------------- sending

    /** Forwards a request within a new client transaction, remembering the server side. */
    public ClientTransaction sendStateful(SipProvider provider, Request out, ServerTransaction serverTx,
                                          String method, String callId) throws Exception {
        ClientTransaction clientTx = provider.getNewClientTransaction(out);
        clientTx.setApplicationData(serverTx);
        captureOutbound(out, callId);
        clientTx.sendRequest();
        sipMetrics.recordSent(method);
        return clientTx;
    }

    /** Forwards a request with no transaction state, used for the end-to-end ACK. */
    public void sendStateless(SipProvider provider, Request out, String method, String callId) throws Exception {
        captureOutbound(out, callId);
        provider.sendRequest(out);
        sipMetrics.recordSent(method);
    }

    public Response respond(ServerTransaction serverTx, Request request, int status,
                            Header... extraHeaders) throws Exception {
        Response response = stack.getMessageFactory().createResponse(status, request);
        for (Header header : extraHeaders) {
            if (header != null) {
                response.addHeader(header);
            }
        }
        if (serverTx != null) {
            serverTx.sendResponse(response);
        } else {
            stack.getSipProvider().sendResponse(response);
        }
        captureOutboundResponse(request, response);
        sipMetrics.recordSent(String.valueOf(status));
        return response;
    }

    public void reject(ServerTransaction serverTx, Request request, int status) {
        try {
            respond(serverTx, request, status);
        } catch (Exception e) {
            log.error("Failed to send {} for {}", status, request.getMethod(), e);
        }
    }

    // --------------------------------------------------------------------- capture

    public void captureInbound(Message message, String callId) {
        capture(HepCaptureService.Direction.INBOUND,
                SipMessages.topViaHost(message), SipMessages.topViaPort(message),
                props.getSelfHost(), props.getSelfPort(), callId, message.toString());
    }

    public void captureOutbound(Request request, String callId) {
        NextHop hop = nextHopOf(request);
        capture(HepCaptureService.Direction.OUTBOUND,
                props.getSelfHost(), props.getSelfPort(), hop.host(), hop.port(),
                callId, request.toString());
    }

    /** Responses travel back down the Via chain, so the destination is the next Via. */
    public void captureOutboundResponse(Message original, Response response) {
        capture(HepCaptureService.Direction.OUTBOUND,
                props.getSelfHost(), props.getSelfPort(),
                SipMessages.topViaHost(original), SipMessages.topViaPort(original),
                SipMessages.callId(response), response.toString());
    }

    public void capture(HepCaptureService.Direction direction, String srcHost, int srcPort,
                        String dstHost, int dstPort, String correlationId, String payload) {
        hep.capture(direction, isUdp(), SipMessages.resolve(srcHost), srcPort,
                SipMessages.resolve(dstHost), dstPort, correlationId, payload);
    }

    /** Where the request will actually go: the top Route if present, else the Request-URI. */
    public NextHop nextHopOf(Request request) {
        RouteHeader route = (RouteHeader) request.getHeader(RouteHeader.NAME);
        URI uri = route != null ? route.getAddress().getURI() : request.getRequestURI();
        if (uri instanceof SipURI sipUri) {
            return new NextHop(sipUri.getHost(), SipMessages.portOrDefault(sipUri));
        }
        return new NextHop("0.0.0.0", 5060);
    }

    public String transport() {
        return stack.getListeningPoint().getTransport();
    }

    private boolean isUdp() {
        return "udp".equalsIgnoreCase(transport());
    }

    public SipStackManager stack() {
        return stack;
    }

    public record NextHop(String host, int port) {
    }
}
