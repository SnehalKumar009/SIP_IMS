package com.snehal.ims.icscf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.snehal.ims.telecom.hep.HepCaptureService;
import com.snehal.ims.telecom.metrics.SipMetrics;
import com.snehal.ims.telecom.sip.SipStackManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import javax.sip.SipFactory;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.HeaderFactory;
import javax.sip.header.RouteHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * RFC 3261 §16.4 route preprocessing in {@link IcscfSipListener#popOwnRoute(Request)}.
 *
 * <p>The P-CSCF addresses the I-CSCF by its Service name, not its pod IP. If that Route
 * is not recognised as ours it survives under the S-CSCF Route, and on a terminating
 * call the S-CSCF follows it back to the I-CSCF, which then runs an LIR on the callee's
 * Contact and answers 404.</p>
 */
class IcscfRoutingTest {

    private static final String SELF_HOST = "10.1.13.15";
    private static final String SERVICE = "i-cscf.ims-core.svc.cluster.local";
    private static final String SCSCF = "s-cscf-0.s-cscf.ims-core.svc.cluster.local";

    private static AddressFactory af;
    private static HeaderFactory hf;
    private static MessageFactory mf;

    @BeforeAll
    static void factories() throws Exception {
        SipFactory factory = SipFactory.getInstance();
        factory.setPathName("gov.nist");
        af = factory.createAddressFactory();
        hf = factory.createHeaderFactory();
        mf = factory.createMessageFactory();
    }

    @Test
    void routeNamingOurServiceIsPopped() throws Exception {
        // What the P-CSCF sends: Route <sip:i-cscf.ims-core.svc.cluster.local:5060;lr>.
        Request request = invite();
        request.addFirst(route(SERVICE, 5060));

        assertThat(listener().popOwnRoute(request)).isTrue();
        assertThat(request.getHeader(RouteHeader.NAME)).isNull();
    }

    @Test
    void routeNamingOurPodAddressIsPopped() throws Exception {
        Request request = invite();
        request.addFirst(route(SELF_HOST, 5060));

        assertThat(listener().popOwnRoute(request)).isTrue();
        assertThat(request.getHeader(RouteHeader.NAME)).isNull();
    }

    @Test
    void routeNamingAnotherProxyIsLeftInPlace() throws Exception {
        Request request = invite();
        request.addFirst(route(SCSCF, 5060));

        assertThat(listener().popOwnRoute(request)).isFalse();
        assertThat(topRouteHost(request)).isEqualTo(SCSCF);
    }

    @Test
    void ourNameOnAnotherPortIsNotUs() throws Exception {
        Request request = invite();
        request.addFirst(route(SERVICE, 5070));

        assertThat(listener().popOwnRoute(request)).isFalse();
        assertThat(topRouteHost(request)).isEqualTo(SERVICE);
    }

    @Test
    void onlyTheTopRouteIsConsidered() throws Exception {
        // Ours sits below another proxy's Route: not ours to remove yet.
        Request request = invite();
        request.addFirst(route(SERVICE, 5060));
        request.addFirst(route(SCSCF, 5060));

        assertThat(listener().popOwnRoute(request)).isFalse();
        assertThat(topRouteHost(request)).isEqualTo(SCSCF);
    }

    // ----------------------------------------------------------------------- helpers

    private static IcscfSipListener listener() {
        IcscfProperties props = new IcscfProperties();
        props.setSelfHost(SELF_HOST);
        props.setSelfPort(5060);
        props.setAliases(List.of(SERVICE));
        return new IcscfSipListener(mock(SipStackManager.class), mock(HepCaptureService.class),
                new SipMetrics(new SimpleMeterRegistry()), mock(CxClient.class),
                new ScscfSelector(props), props);
    }

    private static Request invite() throws Exception {
        return mf.createRequest(
                af.createURI("sip:bob@ims.snehal.com"),
                Request.INVITE,
                hf.createCallIdHeader("test-call-id@icscf"),
                hf.createCSeqHeader(1L, Request.INVITE),
                hf.createFromHeader(af.createAddress("sip:alice@ims.snehal.com"), "alice-tag"),
                hf.createToHeader(af.createAddress("sip:bob@ims.snehal.com"), null),
                List.of(hf.createViaHeader("10.1.13.16", 5060, "udp", null)),
                hf.createMaxForwardsHeader(70));
    }

    private static RouteHeader route(String host, int port) throws Exception {
        SipURI uri = af.createSipURI(null, host);
        uri.setPort(port);
        uri.setLrParam();
        return hf.createRouteHeader(af.createAddress(uri));
    }

    private static String topRouteHost(Request request) {
        RouteHeader top = (RouteHeader) request.getHeader(RouteHeader.NAME);
        return top == null ? null : ((SipURI) top.getAddress().getURI()).getHost();
    }
}
