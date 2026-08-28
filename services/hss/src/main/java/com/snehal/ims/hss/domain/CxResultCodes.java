package com.snehal.ims.hss.domain;

/**
 * Numeric Cx result codes, mirroring the Diameter base Result-Code and Cx
 * Experimental-Result-Code AVPs (3GPP TS 29.229) carried as plain integers here.
 */
public final class CxResultCodes {

    private CxResultCodes() {
    }

    // ---- Diameter base Result-Code AVP ----
    public static final int DIAMETER_SUCCESS = 2001;
    public static final int DIAMETER_UNABLE_TO_COMPLY = 5012;

    // ---- Cx Experimental-Result-Code AVP ----
    public static final int DIAMETER_FIRST_REGISTRATION = 2001;
    public static final int DIAMETER_SUBSEQUENT_REGISTRATION = 2002;
    public static final int DIAMETER_UNREGISTERED_SERVICE = 2003;
    public static final int DIAMETER_SUCCESS_SERVER_NAME_NOT_STORED = 2004;

    public static final int DIAMETER_ERROR_USER_UNKNOWN = 5001;
    public static final int DIAMETER_ERROR_IDENTITIES_DONT_MATCH = 5002;
    public static final int DIAMETER_ERROR_IDENTITY_NOT_REGISTERED = 5003;
    public static final int DIAMETER_ERROR_ROAMING_NOT_ALLOWED = 5004;
    public static final int DIAMETER_ERROR_IDENTITY_ALREADY_REGISTERED = 5005;
    public static final int DIAMETER_ERROR_AUTH_SCHEME_NOT_SUPPORTED = 5006;
    public static final int DIAMETER_ERROR_IN_ASSIGNMENT_TYPE = 5007;
}
