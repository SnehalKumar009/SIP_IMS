package com.snehal.ims.scscf.sip;

import com.snehal.ims.hss.grpc.AuthenticationVector;
import com.snehal.ims.hss.grpc.MultimediaAuthAnswer;
import com.snehal.ims.hss.grpc.ServerAssignmentAnswer;
import com.snehal.ims.hss.grpc.ServerAssignmentType;
import com.snehal.ims.scscf.RegisterRateLimiter;
import com.snehal.ims.scscf.ScscfMetrics;
import com.snehal.ims.scscf.ScscfProperties;
import com.snehal.ims.scscf.auth.DigestAuthenticator;
import com.snehal.ims.scscf.cx.CxClient;
import com.snehal.ims.scscf.cx.CxResults;
import com.snehal.ims.scscf.ifc.ServiceProfile;
import com.snehal.ims.scscf.ifc.ServiceProfileParser;
import com.snehal.ims.scscf.ifc.IfcEvaluator;
import com.snehal.ims.scscf.registrar.Binding;
import com.snehal.ims.scscf.registrar.RegistrationStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import javax.sip.ServerTransaction;
import javax.sip.header.AuthorizationHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.WWWAuthenticateHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The registrar. Authenticates REGISTER with SIP Digest against credentials pulled from
 * the HSS, maintains the contact bindings, reports the assignment back over Cx, and hands
 * the UE the Service-Route it must use for subsequent originating requests.
 */
@Component
public class RegistrarHandler {

    private static final Logger log = LoggerFactory.getLogger(RegistrarHandler.class);

    private final ProxyForwarder proxy;
    private final RegistrationStore store;
    private final DigestAuthenticator authenticator;
    private final CxClient cx;
    private final ServiceProfileParser profileParser;
    private final IfcEvaluator ifc;
    private final RegisterRateLimiter rateLimiter;
    private final ScscfMetrics metrics;
    private final ScscfProperties props;

    public RegistrarHandler(ProxyForwarder proxy,
                            RegistrationStore store,
                            DigestAuthenticator authenticator,
                            CxClient cx,
                            ServiceProfileParser profileParser,
                            IfcEvaluator ifc,
                            RegisterRateLimiter rateLimiter,
                            ScscfMetrics metrics,
                            ScscfProperties props) {
        this.proxy = proxy;
        this.store = store;
        this.authenticator = authenticator;
        this.cx = cx;
        this.profileParser = profileParser;
        this.ifc = ifc;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.props = props;
    }

    public void handle(Request request, ServerTransaction serverTx) throws Exception {
        String impu = SipMessages.toAor(request);
        if (impu == null) {
            proxy.reject(serverTx, request, Response.BAD_REQUEST);
            return;
        }

        if (!rateLimiter.tryAcquire(impu)) {
            metrics.recordRateLimited();
            log.warn("REGISTER rate limit exceeded for impu={}", impu);
            proxy.reject(serverTx, request, Response.SERVICE_UNAVAILABLE);
            return;
        }

        String clientIp = SipMessages.topViaHost(request);
        AuthorizationHeader auth = (AuthorizationHeader) request.getHeader(AuthorizationHeader.NAME);

        if (auth == null) {
            challenge(request, serverTx, impu, SipMessages.deriveImpi(null, impu), clientIp, false);
            return;
        }

        DigestAuthenticator.Verification verification =
                authenticator.verify(auth, Request.REGISTER, clientIp);
        switch (verification.result()) {
            case STALE -> {
                challenge(request, serverTx, impu,
                        SipMessages.deriveImpi(auth.getUsername(), impu), clientIp, true);
                return;
            }
            case FAILED -> {
                metrics.recordAuthFailure();
                log.warn("Digest authentication failed for impu={} from {}", impu, clientIp);
                // 3GPP: a failed authentication clears any assignment the HSS holds, so a
                // hijacked identity cannot keep receiving terminating traffic here.
                cx.serverAssignment(impu, verification.impi(),
                        ServerAssignmentType.SERVER_ASSIGNMENT_AUTHENTICATION_FAILURE);
                store.removeAll(impu);
                ifc.forget(impu);
                proxy.reject(serverTx, request, Response.FORBIDDEN);
                return;
            }
            default -> {
                // authenticated, fall through
            }
        }

        register(request, serverTx, impu, verification.impi());
    }

    // ------------------------------------------------------------------ challenge

    private void challenge(Request request, ServerTransaction serverTx, String impu,
                           String impi, String clientIp, boolean stale) throws Exception {
        MultimediaAuthAnswer maa = cx.multimediaAuth(impu, impi, authenticator.algorithm().cxScheme());
        Integer error = CxResults.toSipStatus(maa.getResultCode(), maa.getExperimentalResultCode());
        if (error != null) {
            log.info("MAR rejected impu={} result={} experimental={}", impu,
                    maa.getResultCode(), maa.getExperimentalResultCode());
            proxy.reject(serverTx, request, error);
            return;
        }

        AuthenticationVector vector = selectVector(maa);
        if (vector == null) {
            log.error("HSS returned no usable {} vector for impu={}",
                    authenticator.algorithm().cxScheme(), impu);
            proxy.reject(serverTx, request, Response.SERVER_INTERNAL_ERROR);
            return;
        }

        // The challenge realm must be the realm the HSS derived H(A1) under, or no
        // response the UE computes can ever verify.
        WWWAuthenticateHeader header = authenticator.challenge(
                vector.getRealm(), impi, clientIp, vector.getAuthorization(), stale);
        metrics.recordChallenge();
        proxy.respond(serverTx, request, Response.UNAUTHORIZED, header);
    }

    private AuthenticationVector selectVector(MultimediaAuthAnswer maa) {
        String wanted = authenticator.algorithm().cxScheme();
        for (AuthenticationVector vector : maa.getVectorsList()) {
            if (wanted.equalsIgnoreCase(vector.getScheme())) {
                return vector;
            }
        }
        return null;
    }

    // --------------------------------------------------------------- registration

    private void register(Request request, ServerTransaction serverTx,
                          String impu, String impi) throws Exception {
        List<ContactHeader> contacts = contactsOf(request);
        int headerExpires = SipMessages.expiresHeader(request);
        ScscfProperties.Registration config = props.getRegistration();

        boolean wildcardRemoval = contacts.size() == 1
                && contacts.get(0).getAddress().isWildcard()
                && effectiveExpires(contacts.get(0), headerExpires, config) == 0;

        // A REGISTER with no Contact is a query for the current bindings.
        if (contacts.isEmpty()) {
            respondWithBindings(request, serverTx, impu, 0);
            return;
        }

        if (wildcardRemoval || allExpiringNow(contacts, headerExpires, config)) {
            deregister(request, serverTx, impu, impi);
            return;
        }

        int requested = effectiveExpires(contacts.get(0), headerExpires, config);
        if (requested < config.getMinExpires()) {
            HeaderFactory hf = proxy.stack().getHeaderFactory();
            Header minExpires = hf.createHeader(SipMessages.MIN_EXPIRES,
                    String.valueOf(config.getMinExpires()));
            proxy.respond(serverTx, request, Response.INTERVAL_TOO_BRIEF, minExpires);
            return;
        }
        int granted = Math.min(requested, config.getMaxExpires());

        List<Binding> existing = store.find(impu);
        if (isReplay(request, existing)) {
            log.warn("Out-of-order REGISTER for impu={} (cseq={})", impu, SipMessages.cseq(request));
            proxy.reject(serverTx, request, Response.SERVER_INTERNAL_ERROR);
            return;
        }

        ServerAssignmentType assignment = existing.isEmpty()
                ? ServerAssignmentType.SERVER_ASSIGNMENT_REGISTRATION
                : ServerAssignmentType.SERVER_ASSIGNMENT_RE_REGISTRATION;
        ServerAssignmentAnswer saa = cx.serverAssignment(impu, impi, assignment);
        Integer error = CxResults.toSipStatus(saa.getResultCode(), saa.getExperimentalResultCode());
        if (error != null) {
            log.info("SAR rejected impu={} result={} experimental={}", impu,
                    saa.getResultCode(), saa.getExperimentalResultCode());
            proxy.reject(serverTx, request, error);
            return;
        }

        ServiceProfile profile = profileParser.parse(saa.getUserProfile());
        ifc.store(impu, profile);

        Instant expiresAt = Instant.now().plusSeconds(granted);
        List<String> path = SipMessages.pathHeaders(request);
        long cseq = SipMessages.cseq(request);
        String callId = SipMessages.callId(request);

        for (ContactHeader contact : contacts) {
            String contactUri = contact.getAddress().getURI().toString();
            Binding binding = new Binding(impu, impi, contactUri, callId, cseq, path,
                    contact.getParameter("+sip.instance"),
                    parseRegId(contact.getParameter("reg-id")),
                    qValueOf(contact), expiresAt);
            store.save(binding);
        }
        enforceBindingCap(impu);

        metrics.recordRegistration(existing.isEmpty() ? "register" : "refresh");
        log.info("Registered impu={} contacts={} expires={}s", impu, contacts.size(), granted);
        respondWithBindings(request, serverTx, impu, granted);
    }

    private void deregister(Request request, ServerTransaction serverTx,
                            String impu, String impi) throws Exception {
        ServerAssignmentAnswer saa = cx.serverAssignment(impu, impi,
                ServerAssignmentType.SERVER_ASSIGNMENT_USER_DEREGISTRATION);
        Integer error = CxResults.toSipStatus(saa.getResultCode(), saa.getExperimentalResultCode());
        if (error != null) {
            proxy.reject(serverTx, request, error);
            return;
        }
        store.removeAll(impu);
        ifc.forget(impu);
        metrics.recordRegistration("deregister");
        log.info("De-registered impu={}", impu);

        HeaderFactory hf = proxy.stack().getHeaderFactory();
        proxy.respond(serverTx, request, Response.OK, hf.createExpiresHeader(0));
    }

    /**
     * Bounds memory per identity. The soonest-expiring binding is dropped rather than the
     * new one rejected, so a device that legitimately re-registers is never locked out by
     * stale flows.
     */
    private void enforceBindingCap(String impu) {
        int cap = props.getRegistration().getMaxBindingsPerAor();
        List<Binding> bindings = store.find(impu);
        if (bindings.size() <= cap) {
            return;
        }
        bindings.stream()
                .sorted(java.util.Comparator.comparing(Binding::expiresAt))
                .limit(bindings.size() - (long) cap)
                .forEach(binding -> {
                    log.warn("Binding cap {} reached for impu={}, evicting {}", cap, impu, binding.key());
                    store.remove(impu, binding.key());
                });
    }

    // -------------------------------------------------------------------- helpers

    private void respondWithBindings(Request request, ServerTransaction serverTx,
                                     String impu, int granted) throws Exception {
        HeaderFactory hf = proxy.stack().getHeaderFactory();
        List<Header> headers = new ArrayList<>();

        Instant now = Instant.now();
        for (Binding binding : store.find(impu)) {
            headers.add(hf.createHeader(ContactHeader.NAME,
                    "<" + binding.contactUri() + ">;expires=" + binding.secondsRemaining(now)));
        }
        headers.add(hf.createExpiresHeader(granted));

        // Tells the UE which S-CSCF its originating requests must traverse.
        headers.add(hf.createHeader(SipMessages.SERVICE_ROUTE, "<" + proxy.ownUri() + ">"));
        headers.add(hf.createHeader(SipMessages.P_ASSOCIATED_URI, "<" + impu + ">"));

        proxy.respond(serverTx, request, Response.OK, headers.toArray(new Header[0]));
    }

    @SuppressWarnings("unchecked")
    private static List<ContactHeader> contactsOf(Request request) {
        List<ContactHeader> contacts = new ArrayList<>();
        ListIterator<ContactHeader> headers =
                (ListIterator<ContactHeader>) request.getHeaders(ContactHeader.NAME);
        while (headers != null && headers.hasNext()) {
            contacts.add(headers.next());
        }
        return contacts;
    }

    private static int effectiveExpires(ContactHeader contact, int headerExpires,
                                        ScscfProperties.Registration config) {
        if (contact.getExpires() >= 0) {
            return contact.getExpires();
        }
        return headerExpires >= 0 ? headerExpires : config.getDefaultExpires();
    }

    private static boolean allExpiringNow(List<ContactHeader> contacts, int headerExpires,
                                          ScscfProperties.Registration config) {
        return contacts.stream().allMatch(c -> effectiveExpires(c, headerExpires, config) == 0);
    }

    /**
     * RFC 3261 §10.3: a REGISTER that reuses a Call-ID with a CSeq that does not advance
     * is a retransmission or a replay and must not overwrite a newer binding.
     */
    private static boolean isReplay(Request request, List<Binding> existing) {
        String callId = SipMessages.callId(request);
        long cseq = SipMessages.cseq(request);
        return existing.stream()
                .anyMatch(b -> b.callId() != null && b.callId().equals(callId) && cseq <= b.cseq());
    }

    private static int qValueOf(ContactHeader contact) {
        float q = contact.getQValue();
        return q < 0 ? 1000 : Math.round(q * 1000);
    }

    private static Integer parseRegId(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
