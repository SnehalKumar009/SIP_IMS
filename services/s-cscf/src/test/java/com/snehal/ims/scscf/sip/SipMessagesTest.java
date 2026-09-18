package com.snehal.ims.scscf.sip;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sip.SipFactory;
import javax.sip.address.AddressFactory;
import javax.sip.header.HeaderFactory;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SipMessagesTest {

    private MessageFactory messages;

    @BeforeEach
    void setUp() throws Exception {
        SipFactory factory = SipFactory.getInstance();
        factory.setPathName("gov.nist");
        messages = factory.createMessageFactory();
        AddressFactory addresses = factory.createAddressFactory();
        HeaderFactory headers = factory.createHeaderFactory();
        assertThat(addresses).isNotNull();
        assertThat(headers).isNotNull();
    }

    @Test
    void readsPathHeadersInWireOrder() throws Exception {
        Request request = parse("""
                REGISTER sip:ims.snehal.com SIP/2.0\r
                Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r
                Path: <sip:p-cscf.ims-core:5060;lr>\r
                Path: <sip:edge.ims-core:5060;lr>\r
                To: <sip:alice@ims.snehal.com>\r
                From: <sip:alice@ims.snehal.com>;tag=1\r
                Call-ID: call-1\r
                CSeq: 4 REGISTER\r
                Max-Forwards: 70\r
                Content-Length: 0\r
                \r
                """);

        assertThat(SipMessages.pathHeaders(request))
                .containsExactly("<sip:p-cscf.ims-core:5060;lr>", "<sip:edge.ims-core:5060;lr>");
    }

    @Test
    void readsIdentitiesAndSequencing() throws Exception {
        Request request = parse("""
                REGISTER sip:ims.snehal.com SIP/2.0\r
                Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1;received=203.0.113.5;rport=41234\r
                To: <sip:alice@ims.snehal.com>;tag=abc\r
                From: <sip:alice@ims.snehal.com>;tag=1\r
                Call-ID: call-1\r
                CSeq: 4 REGISTER\r
                Expires: 120\r
                Max-Forwards: 70\r
                Content-Length: 0\r
                \r
                """);

        assertThat(SipMessages.toAor(request)).isEqualTo("sip:alice@ims.snehal.com");
        assertThat(SipMessages.fromAor(request)).isEqualTo("sip:alice@ims.snehal.com");
        assertThat(SipMessages.callId(request)).isEqualTo("call-1");
        assertThat(SipMessages.cseq(request)).isEqualTo(4);
        assertThat(SipMessages.expiresHeader(request)).isEqualTo(120);
    }

    @Test
    void prefersReceivedAndRportForTheSourceAddress() throws Exception {
        Request request = parse("""
                INVITE sip:bob@ims.snehal.com SIP/2.0\r
                Via: SIP/2.0/UDP 192.168.1.5:5060;branch=z9hG4bK1;received=203.0.113.5;rport=41234\r
                To: <sip:bob@ims.snehal.com>\r
                From: <sip:alice@ims.snehal.com>;tag=1\r
                Call-ID: call-2\r
                CSeq: 1 INVITE\r
                Max-Forwards: 70\r
                Content-Length: 0\r
                \r
                """);

        // The Via host is the UE's private address; only received/rport describe where
        // the packet actually came from.
        assertThat(SipMessages.topViaHost(request)).isEqualTo("203.0.113.5");
        assertThat(SipMessages.topViaPort(request)).isEqualTo(41234);
    }

    @Test
    void stripsUriParametersAndHeaders() {
        assertThat(SipMessages.stripUriParams("sip:alice@ims.snehal.com;transport=udp"))
                .isEqualTo("sip:alice@ims.snehal.com");
        assertThat(SipMessages.stripUriParams("sip:alice@ims.snehal.com?subject=x"))
                .isEqualTo("sip:alice@ims.snehal.com");
        assertThat(SipMessages.stripUriParams("sip:alice@ims.snehal.com"))
                .isEqualTo("sip:alice@ims.snehal.com");
    }

    @Test
    void derivesThePrivateIdentityOnlyWhenNoneWasSupplied() {
        assertThat(SipMessages.deriveImpi("alice@ims.snehal.com", "sip:bob@ims.snehal.com"))
                .isEqualTo("alice@ims.snehal.com");
        assertThat(SipMessages.deriveImpi(null, "sip:alice@ims.snehal.com"))
                .isEqualTo("alice@ims.snehal.com");
        assertThat(SipMessages.deriveImpi("  ", "sip:alice@ims.snehal.com"))
                .isEqualTo("alice@ims.snehal.com");
    }

    @Test
    void readsRequestUriAor() throws Exception {
        Request request = parse("""
                INVITE sip:bob@ims.snehal.com;user=phone SIP/2.0\r
                Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r
                To: <sip:bob@ims.snehal.com>\r
                From: <sip:alice@ims.snehal.com>;tag=1\r
                Call-ID: call-3\r
                CSeq: 1 INVITE\r
                Max-Forwards: 70\r
                Content-Length: 0\r
                \r
                """);

        assertThat(SipMessages.requestUriAor(request)).isEqualTo("sip:bob@ims.snehal.com");
    }

    @Test
    void pathHeadersAreEmptyWhenAbsent() throws Exception {
        Request request = parse("""
                REGISTER sip:ims.snehal.com SIP/2.0\r
                Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r
                To: <sip:alice@ims.snehal.com>\r
                From: <sip:alice@ims.snehal.com>;tag=1\r
                Call-ID: call-4\r
                CSeq: 1 REGISTER\r
                Max-Forwards: 70\r
                Content-Length: 0\r
                \r
                """);

        assertThat(SipMessages.pathHeaders(request)).isEqualTo(List.of());
    }

    private Request parse(String raw) throws Exception {
        return messages.createRequest(raw);
    }
}
