package com.snehal.ims.scscf.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.snehal.ims.scscf.ScscfProperties;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Outstanding digest challenges.
 *
 * <p>Each nonce is single-realm, single-client and single-use per nonce-count. Holding the
 * expected H(A1) alongside it means the second leg of a REGISTER is verified locally instead
 * of costing another Cx round-trip, and the credential never outlives the challenge.</p>
 *
 * <p>The cache is bounded and time-limited, so a flood of unanswered challenges cannot grow
 * without limit. State is per-pod (see docs/TECH_DEBT.md TD-028).</p>
 */
@Component
public class NonceStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Cache<String, Entry> nonces;

    public NonceStore(ScscfProperties props) {
        ScscfProperties.Auth auth = props.getAuth();
        this.nonces = Caffeine.newBuilder()
                .expireAfterWrite(auth.getNonceLifetime())
                .maximumSize(auth.getMaxOutstandingNonces())
                .build();
    }

    /**
     * Issues a challenge bound to one client and one credential.
     *
     * @return the opaque nonce value to place in the {@code WWW-Authenticate} header
     */
    public String issue(String impi, String clientIp, String ha1, DigestAlgorithm algorithm) {
        String nonce = randomToken();
        nonces.put(nonce, new Entry(impi, clientIp, ha1, algorithm, randomToken()));
        return nonce;
    }

    public Entry lookup(String nonce) {
        return nonce == null ? null : nonces.getIfPresent(nonce);
    }

    public void invalidate(String nonce) {
        if (nonce != null) {
            nonces.invalidate(nonce);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A live challenge: who it was issued to, and the credential that answers it. */
    public static final class Entry {

        private final String impi;
        private final String clientIp;
        private final String ha1;
        private final DigestAlgorithm algorithm;
        private final String opaque;
        private final AtomicLong highestNonceCount = new AtomicLong();

        Entry(String impi, String clientIp, String ha1, DigestAlgorithm algorithm, String opaque) {
            this.impi = impi;
            this.clientIp = clientIp;
            this.ha1 = ha1;
            this.algorithm = algorithm;
            this.opaque = opaque;
        }

        /**
         * Accepts a nonce-count only if it advances, which rejects a replayed
         * {@code Authorization} header even within the nonce's lifetime.
         */
        public boolean advanceNonceCount(long nonceCount) {
            while (true) {
                long current = highestNonceCount.get();
                if (nonceCount <= current) {
                    return false;
                }
                if (highestNonceCount.compareAndSet(current, nonceCount)) {
                    return true;
                }
            }
        }

        public String impi() { return impi; }
        public String clientIp() { return clientIp; }
        public String ha1() { return ha1; }
        public DigestAlgorithm algorithm() { return algorithm; }
        public String opaque() { return opaque; }
    }
}
