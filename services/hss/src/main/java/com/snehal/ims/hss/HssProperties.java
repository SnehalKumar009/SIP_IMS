package com.snehal.ims.hss;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HSS configuration bound from {@code ims.hss.*}.
 *
 * <p>{@link #realm} is the home network authentication realm used to derive SIP Digest
 * HA1 values; {@link #seedDemoData} controls one-time provisioning of demo subscribers.</p>
 */
@ConfigurationProperties(prefix = "ims.hss")
public class HssProperties {

    /** Home network realm (Digest realm and default IMPU/IMPI domain). */
    private String realm = "ims.snehal.com";

    /** Authentication scheme advertised in Multimedia-Auth answers. */
    private String authScheme = "SIP Digest";

    /** S-CSCF capability set returned to the I-CSCF when it must select an S-CSCF. */
    private String[] serverCapabilities = {"cap-mandatory:core", "cap-optional:telephony"};

    /** Provision demo subscribers on first startup when the store is empty. */
    private boolean seedDemoData = true;

    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }
    public String getAuthScheme() { return authScheme; }
    public void setAuthScheme(String authScheme) { this.authScheme = authScheme; }
    public String[] getServerCapabilities() { return serverCapabilities; }
    public void setServerCapabilities(String[] serverCapabilities) { this.serverCapabilities = serverCapabilities; }
    public boolean isSeedDemoData() { return seedDemoData; }
    public void setSeedDemoData(boolean seedDemoData) { this.seedDemoData = seedDemoData; }
}
