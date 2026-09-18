package com.snehal.ims.scscf.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.snehal.ims.scscf.ScscfProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.sip.SipFactory;
import javax.sip.header.AuthorizationHeader;
import javax.sip.header.HeaderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the digest exchange against headers parsed exactly as a UE would send them,
 * rather than through the setter API, so quoting and formatting quirks are covered too.
 */
class DigestAuthenticatorTest {

    private static final String REALM = "ims.snehal.com";
    private static final String IMPI = "alice@" + REALM;
    private static final String PASSWORD = "alice-secret";
    private static final String DIGEST_URI = "sip:" + REALM;
    private static final String CLIENT_IP = "10.0.0.1";
    private static final String CNONCE = "0a4f113b";

    private HeaderFactory headers;
    private NonceStore nonces;
    private DigestAuthenticator authenticator;
    private String ha1;

    @BeforeEach
    void setUp() throws Exception {
        SipFactory factory = SipFactory.getInstance();
        factory.setPathName("gov.nist");
        headers = factory.createHeaderFactory();

        ScscfProperties props = new ScscfProperties();
        nonces = new NonceStore(props);
        // The stack is only needed to build a challenge header; verification never touches it.
        authenticator = new DigestAuthenticator(null, nonces, props);
        ha1 = md5(IMPI + ":" + REALM + ":" + PASSWORD);
    }

    @Test
    void acceptsACorrectResponse() throws Exception {
        String nonce = nonces.issue(IMPI, CLIENT_IP, ha1, DigestAlgorithm.MD5);
        AuthorizationHeader auth = authorization(nonce, "00000001", expectedResponse(nonce, "00000001"));

        assertThat(authenticator.verify(auth, "REGISTER", CLIENT_IP).result())
                .isEqualTo(DigestAuthenticator.Result.SUCCESS);
    }

    @Test
    void rejectsAWrongResponse() throws Exception {
        String nonce = nonces.issue(IMPI, CLIENT_IP, ha1, DigestAlgorithm.MD5);
        AuthorizationHeader auth = authorization(nonce, "00000001", md5("not-the-password"));

        assertThat(authenticator.verify(auth, "REGISTER", CLIENT_IP).result())
                .isEqualTo(DigestAuthenticator.Result.FAILED);
    }

    @Test
    void unknownNonceIsStaleRatherThanFailed() throws Exception {
        AuthorizationHeader auth = authorization("never-issued", "00000001", md5("anything"));

        // Stale must not be reported as a failure: the UE deserves a fresh challenge,
        // not a 403.
        assertThat(authenticator.verify(auth, "REGISTER", CLIENT_IP).result())
                .isEqualTo(DigestAuthenticator.Result.STALE);
    }

    @Test
    void rejectsAReplayedNonceCount() throws Exception {
        String nonce = nonces.issue(IMPI, CLIENT_IP, ha1, DigestAlgorithm.MD5);
        AuthorizationHeader first = authorization(nonce, "00000001", expectedResponse(nonce, "00000001"));
        assertThat(authenticator.verify(first, "REGISTER", CLIENT_IP).result())
                .isEqualTo(DigestAuthenticator.Result.SUCCESS);

        AuthorizationHeader replay = authorization(nonce, "00000001", expectedResponse(nonce, "00000001"));
        assertThat(authenticator.verify(replay, "REGISTER", CLIENT_IP).result())
                .isEqualTo(DigestAuthenticator.Result.FAILED);
    }

    @Test
    void acceptsAnAdvancingNonceCount() throws Exception {
        String nonce = nonces.issue(IMPI, CLIENT_IP, ha1, DigestAlgorithm.MD5);
        authenticator.verify(authorization(nonce, "00000001", expectedResponse(nonce, "00000001")),
                "REGISTER", CLIENT_IP);

        AuthorizationHeader next = authorization(nonce, "00000002", expectedResponse(nonce, "00000002"));
        assertThat(authenticator.verify(next, "REGISTER", CLIENT_IP).result())
                .isEqualTo(DigestAuthenticator.Result.SUCCESS);
    }

    @Test
    void rejectsANonceReplayedFromAnotherAddress() throws Exception {
        String nonce = nonces.issue(IMPI, CLIENT_IP, ha1, DigestAlgorithm.MD5);
        AuthorizationHeader auth = authorization(nonce, "00000001", expectedResponse(nonce, "00000001"));

        assertThat(authenticator.verify(auth, "REGISTER", "203.0.113.9").result())
                .isEqualTo(DigestAuthenticator.Result.FAILED);
    }

    @Test
    void rejectsAUsernameThatWasNotChallenged() throws Exception {
        String nonce = nonces.issue(IMPI, CLIENT_IP, ha1, DigestAlgorithm.MD5);
        AuthorizationHeader auth = (AuthorizationHeader) headers.createHeader("Authorization",
                "Digest username=\"mallory@" + REALM + "\", realm=\"" + REALM + "\", nonce=\"" + nonce
                        + "\", uri=\"" + DIGEST_URI + "\", response=\"" + expectedResponse(nonce, "00000001")
                        + "\", algorithm=MD5, cnonce=\"" + CNONCE + "\", qop=auth, nc=00000001");

        assertThat(authenticator.verify(auth, "REGISTER", CLIENT_IP).result())
                .isEqualTo(DigestAuthenticator.Result.FAILED);
    }

    @Test
    void responseIsMethodBoundSoACapturedRegisterCannotAuthoriseAnInvite() throws Exception {
        String nonce = nonces.issue(IMPI, CLIENT_IP, ha1, DigestAlgorithm.MD5);
        AuthorizationHeader auth = authorization(nonce, "00000001", expectedResponse(nonce, "00000001"));

        assertThat(authenticator.verify(auth, "INVITE", CLIENT_IP).result())
                .isEqualTo(DigestAuthenticator.Result.FAILED);
    }

    // ------------------------------------------------------------------- helpers

    private AuthorizationHeader authorization(String nonce, String nc, String response) throws Exception {
        return (AuthorizationHeader) headers.createHeader("Authorization",
                "Digest username=\"" + IMPI + "\", realm=\"" + REALM + "\", nonce=\"" + nonce
                        + "\", uri=\"" + DIGEST_URI + "\", response=\"" + response
                        + "\", algorithm=MD5, cnonce=\"" + CNONCE + "\", qop=auth, nc=" + nc);
    }

    private String expectedResponse(String nonce, String nc) {
        String ha2 = md5("REGISTER:" + DIGEST_URI);
        return md5(ha1 + ":" + nonce + ":" + nc + ":" + CNONCE + ":auth:" + ha2);
    }

    private static String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
