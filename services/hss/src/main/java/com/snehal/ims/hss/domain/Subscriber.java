package com.snehal.ims.hss.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A single IMS subscriber binding one private identity (IMPI) to one public
 * identity (IMPU), the credential used for SIP Digest authentication, and the
 * current S-CSCF assignment tracked over the Cx interface.
 *
 * <p>Credentials are persisted only as realm-bound H(A1) digests — the plaintext
 * password is never stored.</p>
 */
@Entity
@Table(name = "subscriber",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_subscriber_impi", columnNames = "impi"),
                @UniqueConstraint(name = "uk_subscriber_impu", columnNames = "impu")
        })
public class Subscriber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Private identity (IMPI), e.g. {@code alice@ims.snehal.com}. */
    @Column(nullable = false)
    private String impi;

    /** Public identity (IMPU), e.g. {@code sip:alice@ims.snehal.com}. */
    @Column(nullable = false)
    private String impu;

    /** Digest realm the credential is scoped to. */
    @Column(nullable = false)
    private String realm;

    /** MD5 H(A1) = H(impi:realm:password), the 3GPP "SIP Digest" credential. */
    @Column(nullable = false, length = 64)
    private String ha1;

    /** SHA-256 H(A1), offered alongside MD5 for RFC 8760 capable clients. */
    @Column(name = "ha1_sha256", length = 128)
    private String ha1Sha256;

    /** SIP URI of the currently assigned serving S-CSCF, or {@code null}. */
    @Column(name = "scscf_name")
    private String scscfName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RegistrationState state = RegistrationState.NOT_REGISTERED;

    /** Service profile / initial Filter Criteria (iFC) XML handed to the S-CSCF. */
    @Column(name = "service_profile", columnDefinition = "text")
    private String serviceProfile;

    protected Subscriber() {
    }

    public Subscriber(String impi, String impu, String realm, String ha1, String ha1Sha256, String serviceProfile) {
        this.impi = impi;
        this.impu = impu;
        this.realm = realm;
        this.ha1 = ha1;
        this.ha1Sha256 = ha1Sha256;
        this.serviceProfile = serviceProfile;
    }

    /** Provisioning entry point: derives both digests and discards the plaintext password. */
    public static Subscriber provision(String impi, String impu, String realm,
                                       String password, String serviceProfile) {
        return new Subscriber(impi, impu, realm,
                DigestCredentials.ha1Md5(impi, realm, password),
                DigestCredentials.ha1Sha256(impi, realm, password),
                serviceProfile);
    }

    public Long getId() { return id; }
    public String getImpi() { return impi; }
    public String getImpu() { return impu; }
    public String getRealm() { return realm; }
    public String getHa1() { return ha1; }
    public String getHa1Sha256() { return ha1Sha256; }
    public String getScscfName() { return scscfName; }
    public void setScscfName(String scscfName) { this.scscfName = scscfName; }
    public RegistrationState getState() { return state; }
    public void setState(RegistrationState state) { this.state = state; }
    public String getServiceProfile() { return serviceProfile; }
    public void setServiceProfile(String serviceProfile) { this.serviceProfile = serviceProfile; }
}
