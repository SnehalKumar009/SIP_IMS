package com.snehal.ims.telecom.sip;

import com.snehal.ims.telecom.hep.HepCaptureService;
import gov.nist.javax.sip.message.SIPMessage;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;
import net.logstash.logback.marker.Markers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Records one SIP message on the wire: mirrors it to Homer over HEPv3 and writes one
 * structured log event for it.
 *
 * <p>Every CSCF calls this wherever a message enters or leaves the pod, so the log
 * stream holds exactly one event per message, separate from the DEBUG lines that
 * describe processing steps. The events go to the {@value #LOGGER_NAME} logger at INFO
 * and carry ECS-style fields, which is what lets Kibana show the signaling path alone:</p>
 *
 * <pre>
 *   event.action            sip-in | sip-out
 *   sip.direction           in | out
 *   sip.summary             "INVITE sip:bob@ims.snehal.com" | "200 OK (INVITE)"
 *   sip.request_uri         requests only
 *   sip.status_code         responses only
 *   sip.cseq                "1 INVITE"
 *   sip.call_id, sip.method from the MDC, so they match the processing lines
 *   source.address/.port, destination.address/.port
 *   sip.message             the full message, LF line endings
 * </pre>
 *
 * <p>Addresses are logged as given (pod IPs or DNS names), so they map to ECS
 * {@code *.address} rather than {@code *.ip}, which would reject host names.</p>
 */
public class SipTrace {

    /** Logger for wire events; filter on it (or on {@code event.action}) to see signaling only. */
    public static final String LOGGER_NAME = "ims.sip.wire";

    private static final Logger wire = LoggerFactory.getLogger(LOGGER_NAME);

    // MDC keys the shared logback encoder renames to sip.call_id / sip.method.
    private static final String MDC_CALL_ID = "callId";
    private static final String MDC_METHOD = "sipMethod";

    private final HepCaptureService hep;

    public SipTrace(HepCaptureService hep) {
        this.hep = hep;
    }

    /**
     * Records a message that entered or left this pod. Never throws: tracing must not
     * disturb signaling.
     */
    public void record(HepCaptureService.Direction direction, boolean udp,
                       String srcHost, int srcPort, String dstHost, int dstPort,
                       Message message) {
        if (message == null) {
            return;
        }
        try {
            // For an inbound message the stack knows the packet's real source; callers
            // can only guess it (top Via, configured next hop), and the guess is wrong
            // for a response that took a different path than the request.
            if (direction == HepCaptureService.Direction.INBOUND
                    && message instanceof SIPMessage nist && nist.getRemoteAddress() != null) {
                srcHost = nist.getRemoteAddress().getHostAddress();
                srcPort = nist.getRemotePort();
            }
            String raw = message.toString();
            String callId = callId(message);
            hep.capture(direction, udp, resolve(srcHost), srcPort, resolve(dstHost), dstPort,
                    callId, raw);
            if (wire.isInfoEnabled()) {
                log(direction, srcHost, srcPort, dstHost, dstPort, message, raw, callId);
            }
        } catch (Exception e) {
            wire.debug("Could not trace SIP message", e);
        }
    }

    private void log(HepCaptureService.Direction direction, String srcHost, int srcPort,
                     String dstHost, int dstPort, Message message, String raw, String callId) {
        boolean inbound = direction == HepCaptureService.Direction.INBOUND;
        String method = method(message);
        String summary = summary(message, method);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event.action", inbound ? "sip-in" : "sip-out");
        fields.put("sip.direction", inbound ? "in" : "out");
        fields.put("sip.summary", summary);
        if (message instanceof Request request) {
            fields.put("sip.request_uri", String.valueOf(request.getRequestURI()));
        } else if (message instanceof Response response) {
            fields.put("sip.status_code", response.getStatusCode());
        }
        CSeqHeader cseq = (CSeqHeader) message.getHeader(CSeqHeader.NAME);
        if (cseq != null) {
            fields.put("sip.cseq", cseq.getSeqNumber() + " " + cseq.getMethod());
        }
        fields.put("source.address", srcHost);
        fields.put("source.port", srcPort);
        fields.put("destination.address", dstHost);
        fields.put("destination.port", dstPort);
        fields.put("sip.message", raw.replace("\r\n", "\n").stripTrailing());

        String text = (inbound ? "IN  " : "OUT ") + summary
                + "  " + srcHost + ":" + srcPort + " -> " + dstHost + ":" + dstPort;

        // Call-ID and method go through the MDC (not the marker) so they land in the
        // same sip.call_id / sip.method fields as the surrounding processing lines,
        // without emitting a second, duplicate key.
        String previousCallId = MDC.get(MDC_CALL_ID);
        String previousMethod = MDC.get(MDC_METHOD);
        try {
            putOrRemove(MDC_CALL_ID, callId);
            putOrRemove(MDC_METHOD, method);
            wire.info(Markers.appendEntries(fields), text);
        } finally {
            putOrRemove(MDC_CALL_ID, previousCallId);
            putOrRemove(MDC_METHOD, previousMethod);
        }
    }

    private static String summary(Message message, String method) {
        if (message instanceof Request request) {
            return request.getMethod() + " " + request.getRequestURI();
        }
        Response response = (Response) message;
        return response.getStatusCode() + " " + response.getReasonPhrase()
                + (method == null ? "" : " (" + method + ")");
    }

    /** Request method, or for a response the method it answers (from CSeq). */
    private static String method(Message message) {
        if (message instanceof Request request) {
            return request.getMethod();
        }
        CSeqHeader cseq = (CSeqHeader) message.getHeader(CSeqHeader.NAME);
        return cseq == null ? null : cseq.getMethod();
    }

    private static String callId(Message message) {
        CallIdHeader header = (CallIdHeader) message.getHeader(CallIdHeader.NAME);
        return header == null ? null : header.getCallId();
    }

    private static void putOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static InetAddress resolve(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return InetAddress.getLoopbackAddress();
        }
    }
}
