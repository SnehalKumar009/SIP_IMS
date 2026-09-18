package com.snehal.ims.scscf.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Digest hash flavours supported by the registrar.
 *
 * <p>{@link #MD5} is 3GPP "SIP Digest" and stays the default for UE interop; {@link #SHA_256}
 * is RFC 8760 and is only usable where the UE advertises support. Each flavour needs its own
 * H(A1) from the HSS, which is why the Multimedia-Auth answer carries both.</p>
 */
public enum DigestAlgorithm {

    MD5("MD5", "MD5", "SIP Digest"),
    SHA_256("SHA-256", "SHA-256", "SIP Digest SHA-256");

    private final String headerValue;
    private final String jcaName;
    private final String cxScheme;

    DigestAlgorithm(String headerValue, String jcaName, String cxScheme) {
        this.headerValue = headerValue;
        this.jcaName = jcaName;
        this.cxScheme = cxScheme;
    }

    public static DigestAlgorithm from(String value) {
        return "SHA-256".equalsIgnoreCase(value) ? SHA_256 : MD5;
    }

    /** Value written to the {@code algorithm} parameter of the challenge. */
    public String headerValue() {
        return headerValue;
    }

    /** Scheme name used to pick the matching vector out of a Multimedia-Auth answer. */
    public String cxScheme() {
        return cxScheme;
    }

    public String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(jcaName);
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(jcaName + " unavailable", e);
        }
    }
}
