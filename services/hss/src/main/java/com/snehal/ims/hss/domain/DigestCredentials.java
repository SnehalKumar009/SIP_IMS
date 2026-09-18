package com.snehal.ims.hss.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derivation of SIP Digest H(A1) values, {@code H(impi:realm:password)} per RFC 7616.
 *
 * <p>The HSS persists only these digests: a database compromise yields material that is
 * realm-bound and useless outside this deployment, rather than reusable passwords.</p>
 */
public final class DigestCredentials {

    /** 3GPP SIP Digest, MD5 flavour — the scheme name advertised in a Multimedia-Auth answer. */
    public static final String SCHEME_MD5 = "SIP Digest";

    /** RFC 8760 SHA-256 flavour, offered alongside MD5 for clients that support it. */
    public static final String SCHEME_SHA256 = "SIP Digest SHA-256";

    private DigestCredentials() {
    }

    public static String ha1Md5(String impi, String realm, String password) {
        return digest("MD5", impi, realm, password);
    }

    public static String ha1Sha256(String impi, String realm, String password) {
        return digest("SHA-256", impi, realm, password);
    }

    private static String digest(String algorithm, String impi, String realm, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] hash = md.digest((impi + ":" + realm + ":" + password).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }
}
