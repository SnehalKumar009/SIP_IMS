package com.snehal.ims.scscf.sip;

import com.snehal.ims.scscf.ifc.ServiceProfile.SessionCase;
import com.snehal.ims.scscf.registrar.RegistrationStore;
import javax.sip.message.Request;
import org.springframework.stereotype.Component;

/**
 * Decides whether a request is being served on the originating or the terminating side.
 *
 * <p>3GPP marks the originating side with an {@code orig} parameter on the Route header
 * that the P-CSCF replays from the Service-Route. That is the authoritative signal, but
 * the P-CSCF does not honour Service-Route yet (docs/TECH_DEBT.md TD-010), so a request
 * whose Request-URI is an identity registered here is treated as terminating — which is
 * what actually happens when the I-CSCF routes a call in after an LIR.</p>
 */
@Component
public class SessionCaseResolver {

    static final String ORIG_PARAM = "orig";

    private final ProxyForwarder proxy;
    private final RegistrationStore store;

    public SessionCaseResolver(ProxyForwarder proxy, RegistrationStore store) {
        this.proxy = proxy;
        this.store = store;
    }

    public SessionCase resolve(Request request) {
        if (proxy.topRouteIsSelf(request) && proxy.topRouteParameter(request, ORIG_PARAM) != null) {
            return SessionCase.ORIGINATING;
        }
        String requestUri = SipMessages.requestUriAor(request);
        if (requestUri != null && !store.find(requestUri).isEmpty()) {
            return SessionCase.TERMINATING;
        }
        return SessionCase.ORIGINATING;
    }
}
