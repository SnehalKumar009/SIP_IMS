package com.snehal.ims.scscf.sip;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
import javax.sip.header.CallIdHeader;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;

/**
 * Stateless helpers for pulling well-known values out of SIP messages.
 */
public final class SipMessages {

    public static final String PATH = "Path";
    public static final String SERVICE_ROUTE = "Service-Route";
    public static final String P_ASSOCIATED_URI = "P-Associated-URI";
    public static final String P_ASSERTED_IDENTITY = "P-Asserted-Identity";
    public static final String MIN_EXPIRES = "Min-Expires";

    private SipMessages() {
    }

    public static String callId(Message message) {
        CallIdHeader header = (CallIdHeader) message.getHeader(CallIdHeader.NAME);
        return header != null ? header.getCallId() : null;
    }

    public static long cseq(Message message) {
        CSeqHeader header = (CSeqHeader) message.getHeader(CSeqHeader.NAME);
        return header != null ? header.getSeqNumber() : 0L;
    }

    /** Public identity in the To header, with URI parameters removed. */
    public static String toAor(Message message) {
        ToHeader to = (ToHeader) message.getHeader(ToHeader.NAME);
        return to == null ? null : stripUriParams(to.getAddress().getURI().toString());
    }

    /** Public identity in the From header, with URI parameters removed. */
    public static String fromAor(Message message) {
        FromHeader from = (FromHeader) message.getHeader(FromHeader.NAME);
        return from == null ? null : stripUriParams(from.getAddress().getURI().toString());
    }

    public static String requestUriAor(Request request) {
        URI uri = request.getRequestURI();
        return uri == null ? null : stripUriParams(uri.toString());
    }

    /** Explicit {@code Expires} header value, or {@code -1} when absent. */
    public static int expiresHeader(Request request) {
        ExpiresHeader header = (ExpiresHeader) request.getHeader(ExpiresHeader.NAME);
        return header == null ? -1 : header.getExpires();
    }

    /**
     * Path headers in wire order (topmost first), each as its raw header value such as
     * {@code <sip:p-cscf:5060;lr>}. This is the return route towards the UE and is
     * replayed as the Route set of a terminating request.
     */
    @SuppressWarnings("unchecked")
    public static List<String> pathHeaders(Request request) {
        List<String> values = new ArrayList<>();
        Iterator<Header> headers = (Iterator<Header>) request.getHeaders(PATH);
        while (headers != null && headers.hasNext()) {
            String value = rawValue(headers.next());
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    /** Strips the {@code Name:} prefix so a header can be reparsed under a different name. */
    public static String rawValue(Header header) {
        String text = header.toString();
        int colon = text.indexOf(':');
        return colon < 0 ? text.trim() : text.substring(colon + 1).trim();
    }

    /** Private identity from the Authorization username, else derived from the IMPU. */
    public static String deriveImpi(String username, String impu) {
        if (username != null && !username.isBlank()) {
            return username;
        }
        if (impu == null) {
            return null;
        }
        int scheme = impu.indexOf(':');
        return scheme >= 0 ? impu.substring(scheme + 1) : impu;
    }

    public static String stripUriParams(String uri) {
        if (uri == null) {
            return null;
        }
        int cut = uri.indexOf(';');
        int header = uri.indexOf('?');
        if (header >= 0 && (cut < 0 || header < cut)) {
            cut = header;
        }
        return cut >= 0 ? uri.substring(0, cut) : uri;
    }

    /** Source host of a message, preferring the {@code received} parameter added for NAT. */
    public static String topViaHost(Message message) {
        ViaHeader via = (ViaHeader) message.getHeader(ViaHeader.NAME);
        if (via == null) {
            return "0.0.0.0";
        }
        String received = via.getReceived();
        return received != null ? received : via.getHost();
    }

    /** Source port of a message, preferring {@code rport} when the peer asked for it. */
    public static int topViaPort(Message message) {
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

    public static int portOrDefault(SipURI uri) {
        return uri.getPort() > 0 ? uri.getPort() : 5060;
    }

    public static InetAddress resolve(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return InetAddress.getLoopbackAddress();
        }
    }
}
