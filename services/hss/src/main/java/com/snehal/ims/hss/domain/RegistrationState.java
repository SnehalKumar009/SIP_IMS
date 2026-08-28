package com.snehal.ims.hss.domain;

/**
 * IMS registration state of a public identity (IMPU), mirroring the S-CSCF
 * assignment lifecycle tracked by the HSS over the Cx interface.
 */
public enum RegistrationState {
    /** No S-CSCF assigned; the identity is not registered. */
    NOT_REGISTERED,
    /** An S-CSCF is assigned and the identity is actively registered. */
    REGISTERED,
    /** No active registration but an S-CSCF is kept assigned to run unregistered services. */
    UNREGISTERED
}
