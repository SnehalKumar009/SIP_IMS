package com.snehal.ims.pcscf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.snehal.ims.telecom.metrics.SipMetrics;
import com.snehal.ims.telecom.sip.SipStackManager;
import com.snehal.ims.telecom.sip.SipTrace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import javax.sip.SipFactory;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.HeaderFactory;
import javax.sip.header.RouteHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Routing decisions made by {@link PcscfSipListener#resolveNextHop(Request)} —
 * RFC 3261 §16.4 route preprocessing plus §16.6 next-hop selection.
 *
 * <p>These are the cases a REGISTER never exercises: REGISTER only ever travels from
 * the UE into the network, so the terminating direction went untested until a call
 * was attempted.</p>
 */
class PcscfRoutingTest {

    private static final String SELF_HOST = "10.1.12.217";
    private static final int SELF_PORT = 5060;
    private static final String ICSCF = "i-cscf.ims-core.svc.cluster.local";
    private static final String SCSCF = "s-cscf-0.s-cscf.ims-core.svc.cluster.local";
    private static final String UE_CONTACT = "sip:bob@192.168.29.169:5060";

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

    // ------------------------------------------------------------------ originating

    @Test
    void requestWithNoRouteSetIsSentUpstreamToTheIcscf() throws Exception {
        Request request = invite(UE_CONTACT);

        PcscfSipListener.NextHop hop = listener().resolveNextHop(request);

        assertThat(hop.host()).isEqualTo(ICSCF);
        assertThat(hop.port()).isEqualTo(5060);
        // The I-CSCF Route must have been inserted for the stack to follow.
        assertThat(topRouteHost(request)).isEqualTo(ICSCF);
    }

    // ------------------------------------------------------------------ terminating

    @Test
    void requestRoutedToUsWithNothingLeftGoesToTheRequestUri() throws Exception {
        // What the S-CSCF produces on a terminating call: Request-URI retargeted onto
        // the UE's contact, and our own Path replayed as the only Route.
        Request request = invite(UE_CONTACT);
        request.addFirst(route(SELF_HOST, SELF_PORT));

        PcscfSipListener.NextHop hop = listener().resolveNextHop(request);

        assertThat(hop.host()).isEqualTo("192.168.29.169");
        assertThat(hop.port()).isEqualTo(5060);
    }

    @Test
    void terminatingRequestIsNotPushedBackToTheIcscf() throws Exception {
        // Regression guard: forwarding a terminating request to the I-CSCF sends it
        // round the loop P->I->S->P until the S-CSCF's spiral detector returns 482.
        Request request = invite(UE_CONTACT);
        request.addFirst(route(SELF_HOST, SELF_PORT));

        PcscfSipListener.NextHop hop = listener().resolveNextHop(request);

        assertThat(hop.host()).isNotEqualTo(ICSCF);
        assertThat(request.getHeader(RouteHeader.NAME)).isNull();
    }

    // --------------------------------------------------------------------- in-dialog

    @Test
    void ourOwnRouteIsPoppedAndTheRestOfTheRouteSetIsFollowed() throws Exception {
        // In-dialog request from the UE: the route set names us, then the S-CSCF.
        Request request = invite("sip:bob@ims.snehal.com");
        request.addFirst(route(SCSCF, 5060));
        request.addFirst(route(SELF_HOST, SELF_PORT));

        PcscfSipListener.NextHop hop = listener().resolveNextHop(request);

        assertThat(hop.host()).isEqualTo(SCSCF);
        assertThat(topRouteHost(request)).isEqualTo(SCSCF);
    }

    @Test
    void aRouteThatIsNotOursIsLeftInPlaceAndFollowed() throws Exception {
        Request request = invite("sip:bob@ims.snehal.com");
        request.addFirst(route(SCSCF, 5060));

        PcscfSipListener.NextHop hop = listener().resolveNextHop(request);

        assertThat(hop.host()).isEqualTo(SCSCF);
        // Not ours, so it must survive for the next hop to consume.
        assertThat(topRouteHost(request)).isEqualTo(SCSCF);
    }

    @Test
    void ourRouteIsMatchedOnHostAndPortTogether() throws Exception {
        // Same host, different port: not us, so it must not be popped.
        Request request = invite(UE_CONTACT);
        request.addFirst(route(SELF_HOST, 5070));

        PcscfSipListener.NextHop hop = listener().resolveNextHop(request);

        assertThat(hop.port()).isEqualTo(5070);
        assertThat(topRouteHost(request)).isEqualTo(SELF_HOST);
    }

    @Test
    void aRouteWithNoPortIsTreatedAsTheDefault5060() throws Exception {
        SipURI uri = af.createSipURI(null, SELF_HOST);
        uri.setLrParam();
        Request request = invite(UE_CONTACT);
        request.addFirst(hf.createRouteHeader(af.createAddress(uri)));

        PcscfSipListener.NextHop hop = listener().resolveNextHop(request);

        // Port omitted means 5060, which is us — so it pops and we fall to the RURI.
        assertThat(hop.host()).isEqualTo("192.168.29.169");
        assertThat(request.getHeader(RouteHeader.NAME)).isNull();
    }

    // ----------------------------------------------------------------------- helpers

    private static PcscfSipListener listener() {
        PcscfProperties props = new PcscfProperties();
        props.setSelfHost(SELF_HOST);
        props.setSelfPort(SELF_PORT);
        props.getNextHop().setHost(ICSCF);
        props.getNextHop().setPort(5060);
        props.getNextHop().setTransport("udp");

        SipStackManager stack = mock(SipStackManager.class);
        when(stack.getAddressFactory()).thenReturn(af);
        when(stack.getHeaderFactory()).thenReturn(hf);

        return new PcscfSipListener(stack, mock(SipTrace.class),
                new SipMetrics(new SimpleMeterRegistry()), new SipRateLimiter(props), props);
    }

    private static Request invite(String requestUri) throws Exception {
        ViaHeader via = hf.createViaHeader("192.168.29.169", 5060, "udp", null);
        return mf.createRequest(
                af.createURI(requestUri),
                Request.INVITE,
                hf.createCallIdHeader("test-call-id@pcscf"),
                hf.createCSeqHeader(1L, Request.INVITE),
                hf.createFromHeader(af.createAddress("sip:alice@ims.snehal.com"), "alice-tag"),
                hf.createToHeader(af.createAddress("sip:bob@ims.snehal.com"), null),
                List.of(via),
                hf.createMaxForwardsHeader(70));
    }

    private static RouteHeader route(String host, int port) throws Exception {
        SipURI uri = af.createSipURI(null, host);
        uri.setPort(port);
        uri.setLrParam();
        Address address = af.createAddress(uri);
        return hf.createRouteHeader(address);
    }

    private static String topRouteHost(Request request) {
        RouteHeader top = (RouteHeader) request.getHeader(RouteHeader.NAME);
        return top == null ? null : ((SipURI) top.getAddress().getURI()).getHost();
    }
}
