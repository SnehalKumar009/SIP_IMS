package com.snehal.ims.scscf.cx;

import javax.sip.message.Response;

/**
 * Cx result codes (3GPP TS 29.229) and their mapping onto SIP statuses.
 */
public final class CxResults {

    public static final int DIAMETER_SUCCESS = 2001;
    public static final int DIAMETER_ERROR_USER_UNKNOWN = 5001;
    public static final int DIAMETER_ERROR_IDENTITIES_DONT_MATCH = 5002;
    public static final int DIAMETER_ERROR_IDENTITY_NOT_REGISTERED = 5003;
    public static final int DIAMETER_ERROR_ROAMING_NOT_ALLOWED = 5004;
    public static final int DIAMETER_ERROR_AUTH_SCHEME_NOT_SUPPORTED = 5006;
    public static final int DIAMETER_ERROR_IN_ASSIGNMENT_TYPE = 5007;

    private CxResults() {
    }

    /** {@code true} when neither the base nor the experimental result signals an error. */
    public static boolean isSuccess(int resultCode, int experimentalResultCode) {
        return errorOf(resultCode, experimentalResultCode) == 0;
    }

    /** Maps a Cx error onto a SIP status, or returns {@code null} for a successful answer. */
    public static Integer toSipStatus(int resultCode, int experimentalResultCode) {
        int error = errorOf(resultCode, experimentalResultCode);
        if (error == 0) {
            return null;
        }
        return switch (error) {
            case DIAMETER_ERROR_USER_UNKNOWN -> Response.NOT_FOUND;
            case DIAMETER_ERROR_IDENTITIES_DONT_MATCH,
                 DIAMETER_ERROR_ROAMING_NOT_ALLOWED -> Response.FORBIDDEN;
            case DIAMETER_ERROR_IDENTITY_NOT_REGISTERED -> Response.TEMPORARILY_UNAVAILABLE;
            case DIAMETER_ERROR_AUTH_SCHEME_NOT_SUPPORTED -> Response.FORBIDDEN;
            default -> Response.SERVICE_UNAVAILABLE;
        };
    }

    private static int errorOf(int resultCode, int experimentalResultCode) {
        if (resultCode >= 5000) {
            return resultCode;
        }
        if (resultCode == 0 && experimentalResultCode >= 5000) {
            return experimentalResultCode;
        }
        return 0;
    }
}
