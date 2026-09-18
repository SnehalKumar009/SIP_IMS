package com.snehal.ims.scscf.auth;

import com.snehal.ims.scscf.ScscfProperties;
import com.snehal.ims.telecom.sip.SipStackManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import javax.sip.header.AuthorizationHeader;
import javax.sip.header.WWWAuthenticateHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * SIP Digest challenge/response per RFC 7616, using the H(A1) the HSS supplies over Cx.
 *
 * <p>The S-CSCF never sees a password: it receives a realm-bound H(A1), recomputes the
 * expected response locally and compares it in constant time.</p>
 */
@Component
public class DigestAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(DigestAuthenticator.class);
    private static final String QOP_AUTH = "auth";

    /** Outcome of checking an {@code Authorization} header. */
    public enum Result {
        /** Credentials verified. */
        SUCCESS,
        /** Wrong response, wrong client, or a replayed nonce-count — reject the request. */
        FAILED,
        /** Nonce unknown or expired — re-challenge with {@code stale=true}. */
        STALE
    }

    public record Verification(Result result, String impi) {
        public boolean isSuccess() {
            return result == Result.SUCCESS;
        }
    }

    private final SipStackManager stack;
    private final NonceStore nonces;
    private final DigestAlgorithm algorithm;

    public DigestAuthenticator(@Lazy SipStackManager stack, NonceStore nonces, ScscfProperties props) {
        this.stack = stack;
        this.nonces = nonces;
        this.algorithm = DigestAlgorithm.from(props.getAuth().getAlgorithm());
    }

    public DigestAlgorithm algorithm() {
        return algorithm;
    }

    /**
     * Builds a {@code WWW-Authenticate} header and registers the matching challenge.
     *
     * @param realm realm the supplied {@code ha1} is scoped to — it must match the realm
     *              the HSS used to derive it, or every response will fail to verify
     */
    public WWWAuthenticateHeader challenge(String realm, String impi, String clientIp,
                                           String ha1, boolean stale) throws Exception {
        String nonce = nonces.issue(impi, clientIp, ha1, algorithm);
        NonceStore.Entry entry = nonces.lookup(nonce);

        WWWAuthenticateHeader header = stack.getHeaderFactory().createWWWAuthenticateHeader("Digest");
        header.setRealm(realm);
        header.setNonce(nonce);
        header.setAlgorithm(algorithm.headerValue());
        header.setQop(QOP_AUTH);
        if (entry != null) {
            header.setOpaque(entry.opaque());
        }
        if (stale) {
            header.setStale(true);
        }
        return header;
    }

    public Verification verify(AuthorizationHeader auth, String method, String clientIp) {
        String nonce = auth.getNonce();
        NonceStore.Entry entry = nonces.lookup(nonce);
        if (entry == null) {
            return new Verification(Result.STALE, auth.getUsername());
        }

        // A nonce is issued to one client; accepting it from another would let an
        // eavesdropper reuse a captured challenge from a different network path.
        if (!entry.clientIp().equals(clientIp)) {
            log.warn("Nonce issued to {} presented from {}", entry.clientIp(), clientIp);
            return new Verification(Result.FAILED, entry.impi());
        }
        if (auth.getUsername() == null || !auth.getUsername().equals(entry.impi())) {
            log.warn("Authorization username {} does not match the challenged identity {}",
                    auth.getUsername(), entry.impi());
            return new Verification(Result.FAILED, entry.impi());
        }

        String qop = auth.getQop();
        String nonceCount = auth.getParameter("nc");
        String cnonce = auth.getCNonce();
        if (qop != null) {
            if (nonceCount == null || cnonce == null) {
                return new Verification(Result.FAILED, entry.impi());
            }
            long nc;
            try {
                nc = Long.parseLong(nonceCount.trim(), 16);
            } catch (NumberFormatException e) {
                return new Verification(Result.FAILED, entry.impi());
            }
            if (!entry.advanceNonceCount(nc)) {
                log.warn("Replayed nonce-count {} for impi={}", nonceCount, entry.impi());
                return new Verification(Result.FAILED, entry.impi());
            }
        }

        // The digest is computed over the URI exactly as the UE sent it, so the raw
        // parameter is used rather than a parsed-and-reserialized URI.
        String digestUri = auth.getParameter("uri");
        if (digestUri == null || auth.getResponse() == null) {
            return new Verification(Result.FAILED, entry.impi());
        }

        DigestAlgorithm alg = entry.algorithm();
        String ha2 = alg.hash(method + ":" + digestUri);
        String expected = qop != null
                ? alg.hash(entry.ha1() + ":" + nonce + ":" + nonceCount + ":" + cnonce + ":" + qop + ":" + ha2)
                : alg.hash(entry.ha1() + ":" + nonce + ":" + ha2);

        boolean matches = constantTimeEquals(expected, auth.getResponse());
        return new Verification(matches ? Result.SUCCESS : Result.FAILED, entry.impi());
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
